package com.storeanalytics.interpretation.validation;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.interpretation.contract.LlmContractResources;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.EvidenceIndexEntry;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Limitation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Validates the flat WeeklyInterpretationContent v2 contract. */
@Component
public final class WeeklyInterpretationV2ResponseValidator
        implements WeeklyInterpretationResponseValidationStrategy {

    private static final int MAX_VIOLATIONS = 100;
    private static final Pattern FORBIDDEN_NARRATIVE_LITERAL = Pattern.compile(
            "[\\p{N}%₽$€]"
    );
    private static final Pattern REVENUE_NARRATIVE = Pattern.compile(
            "(?iu)(выруч|оборот|доход)"
    );
    private static final Pattern PROFITABILITY_NARRATIVE = Pattern.compile(
            "(?iu)(прибыл|марж|рентабель)"
    );
    private static final Set<String> NARRATIVE_FIELDS = Set.of(
            "text", "title", "summary"
    );
    private static final Set<String> INSUFFICIENT_SECTIONS = Set.of(
            "HEADLINE", "WORKLOAD"
    );

    private final ObjectMapper objectMapper;
    private final LlmJsonSchemaValidator schemaValidator;

    public WeeklyInterpretationV2ResponseValidator() {
        this.objectMapper = JsonMapper.builder()
                .findAndAddModules()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build();
        this.schemaValidator = new LlmJsonSchemaValidator(
                LlmContractResources.NEXT_CONTENT_SCHEMA
        );
    }

    @Override
    public int contentSchemaVersion() {
        return LlmContractResources.NEXT_CONTENT_SCHEMA_VERSION;
    }

    @Override
    public LlmResponseValidationResult validate(
            WeeklyInterpretationInput input,
            String responseBody
    ) {
        WeeklyInterpretationInput source = requireNonNull(input, "input");
        String body = requireNonNull(responseBody, "responseBody");
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
            if (!(root instanceof ObjectNode object)) {
                return invalidJson();
            }
            normalizeLimitations(object, source);
            normalizeOptionalNulls(object);
            normalizeBroadActionTargets(object);
            body = objectMapper.writeValueAsString(object);
        } catch (JacksonException exception) {
            return invalidJson();
        }

        List<StructuralValidationViolation> structural;
        try {
            structural = schemaValidator.validate(body);
        } catch (RuntimeException exception) {
            structural = List.of(new StructuralValidationViolation("json", "$"));
        }
        if (!structural.isEmpty()) {
            return LlmResponseValidationResult.invalid(
                    LlmValidationOutcome.STRUCTURAL_INVALID,
                    structural.stream()
                            .limit(MAX_VIOLATIONS)
                            .map(value -> new LlmValidationViolation(
                                    "SCHEMA_" + value.keyword().toUpperCase(),
                                    emptyToRoot(value.path()),
                                    null
                            ))
                            .toList()
            );
        }

        ValidationContext context = new ValidationContext(source);
        validateEmployees(root, context);
        validateSummaryBlocks(root.path("summaryBlocks"), context);
        validateInsights(root.path("insights"), context);
        validateActions(root.path("actions"), context);
        validateTeamRelationships(root.path("teamRelationships"), context);
        validateNarrative(root, "$", context);
        validateRiskEvidenceDimensions(root.path("insights"), context);
        if (!context.violations().isEmpty()) {
            return LlmResponseValidationResult.invalid(
                    LlmValidationOutcome.SEMANTIC_INVALID,
                    context.violations()
            );
        }
        try {
            return LlmResponseValidationResult.valid(
                    objectMapper.writeValueAsString(canonicalize(root))
            );
        } catch (JacksonException exception) {
            return invalidJson();
        }
    }

    private void normalizeOptionalNulls(ObjectNode root) {
        for (JsonNode value : root.path("summaryBlocks")) {
            if (value instanceof ObjectNode summary) {
                putNullIfMissing(summary, "employeeRef");
                putNullIfMissing(summary, "categoryCode");
            }
        }
        for (JsonNode value : root.path("insights")) {
            if (value instanceof ObjectNode insight) {
                putNullIfMissing(insight, "employeeRef");
                putNullIfMissing(insight, "categoryCode");
                putNullIfMissing(insight, "candidateRef");
            }
        }
        for (JsonNode value : root.path("teamRelationships")) {
            if (value instanceof ObjectNode relationship) {
                putNullIfMissing(relationship, "competencyCode");
            }
        }
    }

    private void putNullIfMissing(ObjectNode node, String field) {
        if (!node.has(field)) {
            node.putNull(field);
        }
    }

    private void normalizeBroadActionTargets(ObjectNode root) {
        for (JsonNode value : root.path("actions")) {
            if (!(value instanceof ObjectNode action)) {
                continue;
            }
            String scope = action.path("targetScope").asText();
            if (("STORE".equals(scope) || "TEAM".equals(scope))
                    && action.path("targetEmployeeRefs") instanceof ArrayNode targets) {
                targets.removeAll();
            }
        }
    }

    private void normalizeLimitations(
            ObjectNode root,
            WeeklyInterpretationInput input
    ) {
        ArrayNode values = objectMapper.createArrayNode();
        for (Limitation limitation : input.manifest().limitations()) {
            ObjectNode node = values.addObject();
            node.put("code", limitation.code());
            node.put("scope", limitation.scope().name());
            nullable(node, "employeeRef", limitation.employeeRef());
            nullable(node, "categoryCode", limitation.categoryCode());
            node.put("impact", limitation.impact().name());
            ArrayNode sections = node.putArray("affectedSections");
            canonicalSections(limitation.affectedSections()).forEach(sections::add);
            node.put(
                    "summary",
                    limitation.impact()
                                    == WeeklyInterpretationInput.LimitationImpact.UNAVAILABLE
                            ? "Часть данных недоступна для подтверждённого вывода."
                            : "Качество данных снижает уверенность в части выводов."
            );
            ArrayNode evidence = node.putArray("evidenceRefs");
            limitation.evidenceRefs().forEach(evidence::add);
        }
        root.set("dataLimitations", values);
    }

    private void nullable(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private void validateEmployees(JsonNode root, ValidationContext context) {
        Set<String> actual = new LinkedHashSet<>();
        JsonNode employees = root.path("employees");
        for (int index = 0; index < employees.size(); index++) {
            JsonNode employee = employees.get(index);
            String employeeRef = employee.path("employeeRef").asText();
            String path = "$.employees[" + index + "]";
            if (!actual.add(employeeRef)) {
                context.add("DUPLICATE_EMPLOYEE", path + ".employeeRef", employeeRef);
            }
            WeeklyInterpretationInput.EmployeeFacts facts =
                    context.employeeFacts().get(employeeRef);
            if (facts != null && !facts.analysisStatus().name().equals(
                    employee.path("analysisStatus").asText()
            )) {
                context.add(
                        "EMPLOYEE_STATUS_MISMATCH",
                        path + ".analysisStatus",
                        employeeRef
                );
            }
        }
        if (!actual.equals(context.employeeRefs())) {
            Set<String> difference = new TreeSet<>(context.employeeRefs());
            difference.removeAll(actual);
            actual.stream()
                    .filter(value -> !context.employeeRefs().contains(value))
                    .forEach(difference::add);
            context.add(
                    "EMPLOYEE_SET_MISMATCH",
                    "$.employees",
                    String.join(",", difference)
            );
        }
    }
    private void validateSummaryBlocks(
            JsonNode summaries,
            ValidationContext context
    ) {
        Map<SummaryKey, Integer> counts = new HashMap<>();
        for (int index = 0; index < summaries.size(); index++) {
            JsonNode summary = summaries.get(index);
            String path = "$.summaryBlocks[" + index + "]";
            String employeeRef = validateScope(summary, path, context);
            validateCategory(summary, path, context);
            validateEvidence(
                    summary.path("evidenceRefs"),
                    path + ".evidenceRefs",
                    employeeRef,
                    context
            );
            String section = summary.path("section").asText();
            SummaryKey key = new SummaryKey(
                    summary.path("scope").asText(),
                    employeeRef,
                    section,
                    nullableText(summary.path("categoryCode"))
            );
            counts.merge(key, 1, Integer::sum);
            if (counts.get(key) > 1) {
                context.add("DUPLICATE_SUMMARY_BLOCK", path, employeeRef);
            }
            validateEmployeeSection(employeeRef, section, path, context);
        }
        requireSummaryCount(
                counts, new SummaryKey("STORE", null, "HEADLINE", null),
                "STORE_HEADLINE_COUNT_MISMATCH", context
        );
        requireSummaryCount(
                counts, new SummaryKey("TEAM", null, "TEAM_OVERVIEW", null),
                "TEAM_OVERVIEW_COUNT_MISMATCH", context
        );
        for (String employeeRef : context.employeeRefs()) {
            requireSummaryCount(
                    counts,
                    new SummaryKey("EMPLOYEE", employeeRef, "HEADLINE", null),
                    "EMPLOYEE_HEADLINE_COUNT_MISMATCH",
                    context
            );
            requireSummaryCount(
                    counts,
                    new SummaryKey("EMPLOYEE", employeeRef, "WORKLOAD", null),
                    "EMPLOYEE_WORKLOAD_COUNT_MISMATCH",
                    context
            );
        }
    }

    private void requireSummaryCount(
            Map<SummaryKey, Integer> counts,
            SummaryKey key,
            String code,
            ValidationContext context
    ) {
        if (counts.getOrDefault(key, 0) != 1) {
            context.add(code, "$.summaryBlocks", key.employeeRef());
        }
    }

    private void validateEmployeeSection(
            String employeeRef,
            String section,
            String path,
            ValidationContext context
    ) {
        if (employeeRef == null) {
            return;
        }
        WeeklyInterpretationInput.EmployeeFacts facts =
                context.employeeFacts().get(employeeRef);
        if (facts == null) {
            return;
        }
        if (facts.analysisStatus() == WeeklyInterpretationInput.Sufficiency.INSUFFICIENT
                && !INSUFFICIENT_SECTIONS.contains(section)) {
            context.add("INSUFFICIENT_SECTION_PRESENT", path, employeeRef);
            return;
        }
        if (facts.analysisStatus() != WeeklyInterpretationInput.Sufficiency.LIMITED) {
            return;
        }
        Set<String> available = Set.copyOf(facts.availableSections());
        boolean allowed = switch (section) {
            case "HEADLINE", "WORKLOAD" -> true;
            case "RESULT" -> available.contains("RESULT");
            case "DYNAMICS" -> available.stream()
                    .anyMatch(value -> !"WORKLOAD".equals(value));
            case "CATEGORY_PERFORMANCE" -> available.contains("CATEGORIES");
            case "ADDITIONAL_SALES" -> available.contains("CATEGORIES")
                    || available.contains("ATTACH");
            case "PLAN_OUTLOOK" -> available.contains("PLAN");
            case "TEAM_OVERVIEW" -> false;
            default -> false;
        };
        if (!allowed) {
            context.add("UNAVAILABLE_EMPLOYEE_SECTION", path, employeeRef);
        }
    }

    private void validateInsights(JsonNode insights, ValidationContext context) {
        for (int index = 0; index < insights.size(); index++) {
            JsonNode insight = insights.get(index);
            String path = "$.insights[" + index + "]";
            String employeeRef = validateScope(insight, path, context);
            validateCategory(insight, path, context);
            validateCandidate(insight, path, context);
            validateEvidence(
                    insight.path("evidenceRefs"),
                    path + ".evidenceRefs",
                    employeeRef,
                    context
            );
            if (employeeRef == null) {
                continue;
            }
            WeeklyInterpretationInput.EmployeeFacts facts =
                    context.employeeFacts().get(employeeRef);
            if (facts != null && facts.analysisStatus()
                    == WeeklyInterpretationInput.Sufficiency.INSUFFICIENT) {
                context.add("INSUFFICIENT_INSIGHT_PRESENT", path, employeeRef);
            } else if (facts != null && facts.analysisStatus()
                    == WeeklyInterpretationInput.Sufficiency.LIMITED) {
                validateLimitedInsight(insight, path, facts, context);
            }
        }
    }

    private void validateLimitedInsight(
            JsonNode insight,
            String path,
            WeeklyInterpretationInput.EmployeeFacts facts,
            ValidationContext context
    ) {
        Set<String> available = Set.copyOf(facts.availableSections());
        String theme = insight.path("theme").asText();
        boolean allowed = switch (theme) {
            case "CATEGORY_MIX" -> available.contains("CATEGORIES");
            case "ADDITIONAL_SALES" -> available.contains("CATEGORIES")
                    || available.contains("ATTACH");
            case "ATTACH_RATE" -> available.contains("ATTACH");
            case "TIME_EFFICIENCY" -> available.contains("WORKLOAD");
            case "EMPLOYEE_PERFORMANCE" -> available.contains("RESULT")
                    || available.contains("RATING");
            default -> true;
        };
        if (!allowed) {
            context.add("UNAVAILABLE_EMPLOYEE_SECTION", path, facts.employeeRef());
        }
    }
    private void validateActions(JsonNode actions, ValidationContext context) {
        Set<ActionKey> seen = new HashSet<>();
        for (int index = 0; index < actions.size(); index++) {
            JsonNode action = actions.get(index);
            String path = "$.actions[" + index + "]";
            Set<String> targets = stringValues(action.path("targetEmployeeRefs"));
            validateArrayMembers(
                    action.path("targetEmployeeRefs"),
                    context.employeeRefs(),
                    "UNKNOWN_EMPLOYEE_REF",
                    path + ".targetEmployeeRefs",
                    context
            );
            String scope = action.path("targetScope").asText();
            boolean scopeMatches = "EMPLOYEE".equals(scope)
                    ? !targets.isEmpty()
                    : targets.isEmpty();
            if (!scopeMatches) {
                context.add("ACTION_TARGET_SCOPE_MISMATCH", path, scope);
            }
            for (String target : targets) {
                WeeklyInterpretationInput.EmployeeFacts facts =
                        context.employeeFacts().get(target);
                if (facts != null && facts.analysisStatus()
                        == WeeklyInterpretationInput.Sufficiency.INSUFFICIENT) {
                    context.add("INSUFFICIENT_ACTION_PRESENT", path, target);
                }
            }
            validateEvidence(
                    action.path("evidenceRefs"),
                    path + ".evidenceRefs",
                    null,
                    context
            );
            if ("EMPLOYEE".equals(scope)) {
                requireTargetEvidence(action.path("evidenceRefs"), targets, path, context);
            }
            ActionKey key = new ActionKey(
                    action.path("type").asText(),
                    action.path("horizon").asText(),
                    scope,
                    targets,
                    stringValues(action.path("evidenceRefs"))
            );
            if (!seen.add(key)) {
                context.add("DUPLICATE_RECOMMENDED_ACTION", path, null);
            }
        }
    }

    private void requireTargetEvidence(
            JsonNode evidenceRefs,
            Set<String> targets,
            String path,
            ValidationContext context
    ) {
        Set<String> evidencedEmployees = new HashSet<>();
        evidenceRefs.forEach(value -> {
            EvidenceIndexEntry evidence = context.evidence().get(value.asText());
            if (evidence != null && evidence.employeeRef() != null) {
                evidencedEmployees.add(evidence.employeeRef());
            }
        });
        targets.stream()
                .filter(target -> !evidencedEmployees.contains(target))
                .forEach(target -> context.add(
                        "EMPLOYEE_EVIDENCE_REQUIRED",
                        path + ".evidenceRefs",
                        target
                ));
    }

    private void validateTeamRelationships(
            JsonNode relationships,
            ValidationContext context
    ) {
        Map<String, Set<String>> leaders = new HashMap<>();
        for (int index = 0; index < relationships.size(); index++) {
            JsonNode relationship = relationships.get(index);
            String path = "$.teamRelationships[" + index + "]";
            String type = relationship.path("type").asText();
            String competencyCode = nullableText(
                    relationship.path("competencyCode")
            );
            Set<String> sources = stringValues(
                    relationship.path("sourceEmployeeRefs")
            );
            Set<String> targets = stringValues(
                    relationship.path("targetEmployeeRefs")
            );
            validateArrayMembers(
                    relationship.path("sourceEmployeeRefs"),
                    context.employeeRefs(),
                    "UNKNOWN_EMPLOYEE_REF",
                    path + ".sourceEmployeeRefs",
                    context
            );
            validateArrayMembers(
                    relationship.path("targetEmployeeRefs"),
                    context.employeeRefs(),
                    "UNKNOWN_EMPLOYEE_REF",
                    path + ".targetEmployeeRefs",
                    context
            );
            validateCompetency(competencyCode, path + ".competencyCode", context);
            validateEvidence(
                    relationship.path("evidenceRefs"),
                    path + ".evidenceRefs",
                    null,
                    context
            );
            if (sources.stream().anyMatch(targets::contains)) {
                context.add("MENTOR_TARGET_OVERLAP", path, null);
            }
            Set<String> participants = new HashSet<>(sources);
            participants.addAll(targets);
            for (String participant : participants) {
                WeeklyInterpretationInput.EmployeeFacts facts =
                        context.employeeFacts().get(participant);
                if (facts != null && facts.analysisStatus()
                        == WeeklyInterpretationInput.Sufficiency.INSUFFICIENT) {
                    context.add(
                            "INSUFFICIENT_RELATIONSHIP_PRESENT",
                            path,
                            participant
                    );
                }
            }
            switch (type) {
                case "COMPETENCY_LEADER" -> {
                    if (competencyCode == null || sources.isEmpty()
                            || !targets.isEmpty()) {
                        context.add("RELATIONSHIP_SHAPE_MISMATCH", path, type);
                    } else {
                        leaders.computeIfAbsent(
                                competencyCode,
                                key -> new HashSet<>()
                        ).addAll(sources);
                    }
                }
                case "MOST_IMPROVED" -> {
                    if (competencyCode != null || sources.size() != 1
                            || !targets.isEmpty()) {
                        context.add("RELATIONSHIP_SHAPE_MISMATCH", path, type);
                    }
                }
                case "LEARNING_OPPORTUNITY" -> {
                    if (competencyCode == null || sources.isEmpty()
                            || targets.isEmpty()) {
                        context.add("RELATIONSHIP_SHAPE_MISMATCH", path, type);
                    }
                }
                default -> context.add("RELATIONSHIP_SHAPE_MISMATCH", path, type);
            }
        }
        for (int index = 0; index < relationships.size(); index++) {
            JsonNode relationship = relationships.get(index);
            if (!"LEARNING_OPPORTUNITY".equals(
                    relationship.path("type").asText()
            )) {
                continue;
            }
            String competencyCode = relationship.path("competencyCode").asText();
            Set<String> mentors = stringValues(
                    relationship.path("sourceEmployeeRefs")
            );
            if (!leaders.getOrDefault(competencyCode, Set.of())
                    .containsAll(mentors)) {
                context.add(
                        "MENTOR_NOT_COMPETENCY_LEADER",
                        "$.teamRelationships[" + index + "]",
                        null
                );
            }
        }
    }
    private String validateScope(
            JsonNode item,
            String path,
            ValidationContext context
    ) {
        String scope = item.path("scope").asText();
        String employeeRef = nullableText(item.path("employeeRef"));
        boolean valid = "EMPLOYEE".equals(scope)
                ? employeeRef != null
                : employeeRef == null;
        if (!valid) {
            context.add("SCOPE_EMPLOYEE_MISMATCH", path, employeeRef);
        }
        if (employeeRef != null && !context.employeeRefs().contains(employeeRef)) {
            context.add(
                    "UNKNOWN_EMPLOYEE_REF",
                    path + ".employeeRef",
                    employeeRef
            );
        }
        return "EMPLOYEE".equals(scope) ? employeeRef : null;
    }

    private void validateCategory(
            JsonNode item,
            String path,
            ValidationContext context
    ) {
        String categoryCode = nullableText(item.path("categoryCode"));
        if (categoryCode != null
                && !context.categoryCodes().contains(categoryCode)) {
            context.add(
                    "UNKNOWN_CATEGORY_CODE",
                    path + ".categoryCode",
                    categoryCode
            );
        }
    }

    private void validateCandidate(
            JsonNode insight,
            String path,
            ValidationContext context
    ) {
        String candidateRef = nullableText(insight.path("candidateRef"));
        if (candidateRef != null
                && !context.candidateRefs().contains(candidateRef)) {
            context.add(
                    "UNKNOWN_CANDIDATE_REF",
                    path + ".candidateRef",
                    candidateRef
            );
        }
    }

    private void validateCompetency(
            String value,
            String path,
            ValidationContext context
    ) {
        if (value == null) {
            return;
        }
        boolean allowed = context.competencyCodes().contains(value);
        if (value.startsWith("CATEGORY:")) {
            allowed = context.categoryCodes().contains(
                    value.substring("CATEGORY:".length())
            );
        }
        if (!allowed) {
            context.add("UNKNOWN_COMPETENCY_CODE", path, value);
        }
    }

    private void validateEvidence(
            JsonNode values,
            String path,
            String employeeContext,
            ValidationContext context
    ) {
        boolean hasEmployeeEvidence = false;
        for (int index = 0; index < values.size(); index++) {
            String reference = values.get(index).asText();
            EvidenceIndexEntry evidence = context.evidence().get(reference);
            if (evidence == null || !evidence.available()) {
                context.add(
                        "UNAVAILABLE_EVIDENCE_REF",
                        path + "[" + index + "]",
                        reference
                );
            } else if (employeeContext != null
                    && evidence.employeeRef() != null
                    && !employeeContext.equals(evidence.employeeRef())) {
                context.add(
                        "CROSS_EMPLOYEE_EVIDENCE",
                        path + "[" + index + "]",
                        reference
                );
            } else if (employeeContext != null
                    && employeeContext.equals(evidence.employeeRef())) {
                hasEmployeeEvidence = true;
            }
        }
        if (employeeContext != null && !hasEmployeeEvidence) {
            context.add("EMPLOYEE_EVIDENCE_REQUIRED", path, employeeContext);
        }
    }

    private void validateNarrative(
            JsonNode node,
            String path,
            ValidationContext context
    ) {
        if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                validateNarrative(
                        node.get(index),
                        path + "[" + index + "]",
                        context
                );
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        for (String field : node.propertyNames()) {
            JsonNode value = node.get(field);
            String childPath = path + "." + field;
            if ("dataLimitations".equals(field)) {
                continue;
            }
            if (NARRATIVE_FIELDS.contains(field) && value.isTextual()) {
                String narrative = value.asText();
                if (FORBIDDEN_NARRATIVE_LITERAL.matcher(narrative).find()) {
                    context.add(
                            "FORBIDDEN_NARRATIVE_LITERAL",
                            childPath,
                            null
                    );
                }
                if (context.technicalIdentifiers().stream()
                        .anyMatch(narrative::contains)) {
                    context.add(
                            "FORBIDDEN_TECHNICAL_IDENTIFIER",
                            childPath,
                            null
                    );
                }
            }
            validateNarrative(value, childPath, context);
        }
    }

    private void validateRiskEvidenceDimensions(
            JsonNode insights,
            ValidationContext context
    ) {
        for (int index = 0; index < insights.size(); index++) {
            JsonNode insight = insights.get(index);
            if (!"RISK".equals(insight.path("kind").asText())) {
                continue;
            }
            String narrative = insight.path("title").asText() + " "
                    + insight.path("summary").asText();
            Set<String> evidenceRefs = stringValues(
                    insight.path("evidenceRefs")
            );
            String path = "$.insights[" + index + "]";
            if (REVENUE_NARRATIVE.matcher(narrative).find()
                    && evidenceRefs.stream().noneMatch(
                    WeeklyInterpretationV2ResponseValidator::isRevenueEvidence
            )) {
                context.add("UNSUPPORTED_RISK_DIMENSION", path, "REVENUE");
            }
            if (PROFITABILITY_NARRATIVE.matcher(narrative).find()
                    && evidenceRefs.stream().noneMatch(
                    WeeklyInterpretationV2ResponseValidator::isProfitabilityEvidence
            )) {
                context.add("UNSUPPORTED_RISK_DIMENSION", path, "PROFITABILITY");
            }
        }
    }

    private static boolean isRevenueEvidence(String reference) {
        return reference.contains("NET_REVENUE")
                || reference.contains("REVENUE_SHARE")
                || reference.contains("ADDITIONAL_REVENUE")
                || reference.contains("REVENUE_PER")
                || reference.contains("PLAN:REVENUE");
    }

    private static boolean isProfitabilityEvidence(String reference) {
        return reference.contains("GROSS_PROFIT")
                || reference.contains("MARGIN")
                || reference.contains("PROFIT");
    }
    private void validateArrayMembers(
            JsonNode values,
            Set<String> allowed,
            String code,
            String path,
            ValidationContext context
    ) {
        for (int index = 0; index < values.size(); index++) {
            String value = values.get(index).asText();
            if (!allowed.contains(value)) {
                context.add(code, path + "[" + index + "]", value);
            }
        }
    }

    private Set<String> stringValues(JsonNode values) {
        Set<String> result = new LinkedHashSet<>();
        values.forEach(value -> result.add(value.asText()));
        return result;
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            node.propertyNames().stream().sorted()
                    .forEach(name -> result.set(name, canonicalize(node.get(name))));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            node.forEach(value -> result.add(canonicalize(value)));
            return result;
        }
        return node.deepCopy();
    }

    private LlmResponseValidationResult invalidJson() {
        return LlmResponseValidationResult.invalid(
                LlmValidationOutcome.STRUCTURAL_INVALID,
                List.of(new LlmValidationViolation("INVALID_JSON", "$", null))
        );
    }

    private static Set<String> canonicalSections(List<String> sections) {
        Set<String> result = new LinkedHashSet<>();
        sections.stream()
                .map(WeeklyInterpretationV2ResponseValidator::canonicalSection)
                .forEach(result::add);
        return result;
    }

    private static String canonicalSection(String section) {
        return switch (section) {
            case "CATEGORIES" -> "CATEGORY_PERFORMANCE";
            case "ATTACH" -> "ADDITIONAL_SALES";
            case "PROFIT", "MARGIN" -> "PROFITABILITY";
            case "EMPLOYEES" -> "TEAM_COMPARISON";
            default -> section;
        };
    }

    private static String nullableText(JsonNode value) {
        return value.isNull() ? null : value.asText();
    }

    private static String emptyToRoot(String value) {
        return value == null || value.isBlank() ? "$" : value;
    }

    private record SummaryKey(
            String scope,
            String employeeRef,
            String section,
            String categoryCode
    ) {
    }

    private record ActionKey(
            String type,
            String horizon,
            String targetScope,
            Set<String> targetEmployeeRefs,
            Set<String> evidenceRefs
    ) {

        private ActionKey {
            targetEmployeeRefs = Set.copyOf(targetEmployeeRefs);
            evidenceRefs = Set.copyOf(evidenceRefs);
        }
    }

    private static final class ValidationContext {

        private final Set<String> employeeRefs;
        private final Map<String, WeeklyInterpretationInput.EmployeeFacts> employeeFacts;
        private final Map<String, EvidenceIndexEntry> evidence;
        private final Set<String> candidateRefs;
        private final Set<String> categoryCodes;
        private final Set<String> competencyCodes;
        private final Set<String> technicalIdentifiers;
        private final List<LlmValidationViolation> violations = new ArrayList<>();

        private ValidationContext(WeeklyInterpretationInput input) {
            this.employeeRefs = Set.copyOf(input.manifest().employeeRefs());
            this.employeeFacts = new HashMap<>();
            input.facts().employees().forEach(value -> employeeFacts.put(
                    value.employeeRef(),
                    value
            ));
            this.evidence = new HashMap<>();
            input.facts().store().forEach(value -> addFactEvidence(
                    value,
                    WeeklyInterpretationInput.Scope.STORE,
                    null
            ));
            input.facts().team().forEach(value -> addFactEvidence(
                    value,
                    WeeklyInterpretationInput.Scope.TEAM,
                    null
            ));
            input.facts().employees().forEach(employee -> employee.facts()
                    .forEach(value -> addFactEvidence(
                            value,
                            WeeklyInterpretationInput.Scope.EMPLOYEE,
                            employee.employeeRef()
                    )));
            input.manifest().evidence().forEach(value -> evidence.put(
                    value.evidenceRef(),
                    value
            ));
            this.candidateRefs = Set.copyOf(input.manifest().candidateRefs());
            this.categoryCodes = Set.copyOf(input.manifest().categoryCodes());
            this.competencyCodes = Set.copyOf(input.manifest().competencyCodes());
            Set<String> identifiers = new HashSet<>();
            identifiers.addAll(employeeRefs);
            identifiers.addAll(candidateRefs);
            identifiers.addAll(categoryCodes);
            identifiers.addAll(competencyCodes);
            identifiers.addAll(evidence.keySet());
            identifiers.removeIf(String::isBlank);
            this.technicalIdentifiers = Set.copyOf(identifiers);
        }

        private void addFactEvidence(
                WeeklyInterpretationInput.Fact fact,
                WeeklyInterpretationInput.Scope scope,
                String employeeRef
        ) {
            evidence.putIfAbsent(
                    fact.evidenceRef(),
                    new EvidenceIndexEntry(
                            fact.evidenceRef(),
                            scope,
                            employeeRef,
                            true
                    )
            );
        }

        private void add(String code, String path, String reference) {
            if (violations.size() < MAX_VIOLATIONS) {
                violations.add(new LlmValidationViolation(
                        code,
                        path,
                        reference
                ));
            }
        }

        private Set<String> employeeRefs() {
            return employeeRefs;
        }

        private Map<String, WeeklyInterpretationInput.EmployeeFacts> employeeFacts() {
            return employeeFacts;
        }

        private Map<String, EvidenceIndexEntry> evidence() {
            return evidence;
        }

        private Set<String> candidateRefs() {
            return candidateRefs;
        }

        private Set<String> categoryCodes() {
            return categoryCodes;
        }

        private Set<String> competencyCodes() {
            return competencyCodes;
        }

        private Set<String> technicalIdentifiers() {
            return technicalIdentifiers;
        }

        private List<LlmValidationViolation> violations() {
            return List.copyOf(violations);
        }
    }
}
