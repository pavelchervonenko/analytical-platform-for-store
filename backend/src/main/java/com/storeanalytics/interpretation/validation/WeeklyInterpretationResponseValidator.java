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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Validates provenance and cross-field invariants without judging LLM conclusions. */
@Component
public final class WeeklyInterpretationResponseValidator
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

    private final ObjectMapper objectMapper;
    private final LlmJsonSchemaValidator schemaValidator;

    public WeeklyInterpretationResponseValidator() {
        this.objectMapper = JsonMapper.builder()
                .findAndAddModules()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build();
        this.schemaValidator = new LlmJsonSchemaValidator(
                LlmContractResources.CONTENT_SCHEMA
        );
    }

    @Override
    public int contentSchemaVersion() {
        return LlmContractResources.CONTENT_SCHEMA_VERSION;
    }

    @Override
    public LlmResponseValidationResult validate(
            WeeklyInterpretationInput input,
            String responseBody
    ) {
        WeeklyInterpretationInput source = requireNonNull(input, "input");
        String body = requireNonNull(responseBody, "responseBody");
        try {
            JsonNode normalized = objectMapper.readTree(body);
            normalizeLimitations(normalized, source);
            normalizeInsufficientEmployees(normalized, source);
            body = objectMapper.writeValueAsString(normalized);
        } catch (JacksonException exception) {
            return LlmResponseValidationResult.invalid(
                    LlmValidationOutcome.STRUCTURAL_INVALID,
                    List.of(new LlmValidationViolation("INVALID_JSON", "$", null))
            );
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

        try {
            JsonNode root = objectMapper.readTree(body);
            normalizeLimitations(root, source);
            ValidationContext context = new ValidationContext(source);
            validateEmployees(root, context);
            validateReferences(root.path("store"), "$.store", null, context);
            validateReferences(
                    root.path("teamInsights"), "$.teamInsights", null, context
            );
            validateLimitations(root, context);
            validateTeamRelationships(root.path("teamInsights"), context);
            validateRecommendedActionUniqueness(root, "$", context);
            validateRiskEvidenceDimensions(root, "$", context);
            validateNarrative(root, "$", context);
            validateDuplicateNarrative(root.path("store"), "$.store", context);
            validateDuplicateNarrative(
                    root.path("teamInsights"), "$.teamInsights", context
            );
            JsonNode employees = root.path("employees");
            for (int index = 0; index < employees.size(); index++) {
                JsonNode employee = employees.get(index);
                WeeklyInterpretationInput.EmployeeFacts employeeFacts =
                        context.employeeFacts().get(employee.path("employeeRef").asText());
                if (employeeFacts == null || employeeFacts.analysisStatus()
                        != WeeklyInterpretationInput.Sufficiency.INSUFFICIENT) {
                    validateDuplicateNarrative(
                            employee, "$.employees[" + index + "]", context
                    );
                }
            }
            if (!context.violations().isEmpty()) {
                return LlmResponseValidationResult.invalid(
                        LlmValidationOutcome.SEMANTIC_INVALID,
                        context.violations()
                );
            }
            return LlmResponseValidationResult.valid(
                    objectMapper.writeValueAsString(canonicalize(root))
            );
        } catch (JacksonException exception) {
            return LlmResponseValidationResult.invalid(
                    LlmValidationOutcome.STRUCTURAL_INVALID,
                    List.of(new LlmValidationViolation("INVALID_JSON", "$", null))
            );
        }
    }

    private void normalizeLimitations(
            JsonNode root,
            WeeklyInterpretationInput input
    ) {
        ObjectNode object = (ObjectNode) root;
        object.set(
                "dataLimitations",
                limitationArray(input.manifest().limitations().stream()
                        .filter(value -> value.employeeRef() == null)
                        .toList())
        );
        for (JsonNode employee : object.path("employees")) {
            String employeeRef = employee.path("employeeRef").asText();
            ((ObjectNode) employee).set(
                    "dataLimitations",
                    limitationArray(input.manifest().limitations().stream()
                            .filter(value -> employeeRef.equals(value.employeeRef()))
                            .toList())
            );
        }
    }

    private ArrayNode limitationArray(List<Limitation> limitations) {
        ArrayNode result = objectMapper.createArrayNode();
        for (Limitation limitation : limitations) {
            ObjectNode node = result.addObject();
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
        return result;
    }

    private void nullable(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static Set<String> canonicalSections(List<String> sections) {
        Set<String> result = new LinkedHashSet<>();
        sections.stream().map(WeeklyInterpretationResponseValidator::canonicalSection)
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

    private void normalizeInsufficientEmployees(
            JsonNode root,
            WeeklyInterpretationInput input
    ) {
        Map<String, WeeklyInterpretationInput.Sufficiency> statuses = new HashMap<>();
        input.facts().employees().forEach(value -> statuses.put(
                value.employeeRef(), value.analysisStatus()
        ));
        for (JsonNode employee : root.path("employees")) {
            if (statuses.get(employee.path("employeeRef").asText())
                    != WeeklyInterpretationInput.Sufficiency.INSUFFICIENT) {
                continue;
            }
            ObjectNode object = (ObjectNode) employee;
            List.of(
                    "performanceSummary", "dynamicsSummary",
                    "additionalSalesPerformance", "strength",
                    "attentionArea", "primaryRisk"
            ).forEach(object::putNull);
            ObjectNode categories = (ObjectNode) object.path("categoryPerformance");
            categories.putNull("summary");
            categories.putArray("strengths");
            categories.putArray("attentionAreas");
            categories.putArray("dynamics");
            object.putArray("recommendedActions");
        }
    }

    private void validateEmployees(JsonNode root, ValidationContext context) {
        JsonNode employees = root.path("employees");
        Set<String> actual = new LinkedHashSet<>();
        for (int index = 0; index < employees.size(); index++) {
            JsonNode employee = employees.get(index);
            String path = "$.employees[" + index + "]";
            String employeeRef = employee.path("employeeRef").asText();
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
            if (facts != null
                    && facts.analysisStatus()
                    == WeeklyInterpretationInput.Sufficiency.INSUFFICIENT) {
                validateInsufficientEmployee(employee, path, employeeRef, context);
            } else if (facts != null
                    && facts.analysisStatus()
                    == WeeklyInterpretationInput.Sufficiency.LIMITED) {
                validateLimitedEmployee(employee, path, facts, context);
            }
            validateEmployeeActions(employee, path, employeeRef, context);
            validateReferences(employee, path, employeeRef, context);
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

    private void validateInsufficientEmployee(
            JsonNode employee,
            String path,
            String employeeRef,
            ValidationContext context
    ) {
        List<String> nullable = List.of(
                "performanceSummary", "dynamicsSummary", "additionalSalesPerformance",
                "strength", "attentionArea", "primaryRisk"
        );
        nullable.stream()
                .filter(field -> !employee.path(field).isNull())
                .forEach(field -> context.add(
                        "INSUFFICIENT_SECTION_PRESENT",
                        path + "." + field,
                        employeeRef
                ));
        List<String> arrays = List.of("strengths", "attentionAreas", "dynamics");
        arrays.stream()
                .filter(field -> !employee.path("categoryPerformance")
                        .path(field).isEmpty())
                .forEach(field -> context.add(
                        "INSUFFICIENT_SECTION_PRESENT",
                        path + ".categoryPerformance." + field,
                        employeeRef
                ));
        if (!employee.path("categoryPerformance").path("summary").isNull()) {
            context.add(
                    "INSUFFICIENT_SECTION_PRESENT",
                    path + ".categoryPerformance.summary",
                    employeeRef
            );
        }
        if (!employee.path("recommendedActions").isEmpty()) {
            context.add(
                    "INSUFFICIENT_ACTION_PRESENT",
                    path + ".recommendedActions",
                    employeeRef
            );
        }
    }

    private void validateLimitedEmployee(
            JsonNode employee,
            String path,
            WeeklyInterpretationInput.EmployeeFacts facts,
            ValidationContext context
    ) {
        Set<String> sections = Set.copyOf(facts.availableSections());
        if (!sections.contains("RESULT")
                && !employee.path("performanceSummary").isNull()) {
            context.add("UNAVAILABLE_EMPLOYEE_SECTION",
                    path + ".performanceSummary", facts.employeeRef());
        }
        boolean dynamicsAvailable = sections.stream()
                .anyMatch(value -> !"WORKLOAD".equals(value));
        if (!dynamicsAvailable && !employee.path("dynamicsSummary").isNull()) {
            context.add("UNAVAILABLE_EMPLOYEE_SECTION",
                    path + ".dynamicsSummary", facts.employeeRef());
        }
        JsonNode categories = employee.path("categoryPerformance");
        if (!sections.contains("CATEGORIES")
                && (!categories.path("summary").isNull()
                || !categories.path("strengths").isEmpty()
                || !categories.path("attentionAreas").isEmpty()
                || !categories.path("dynamics").isEmpty())) {
            context.add("UNAVAILABLE_EMPLOYEE_SECTION",
                    path + ".categoryPerformance", facts.employeeRef());
        }
        boolean additionalSalesAvailable = sections.contains("CATEGORIES")
                || sections.contains("ATTACH");
        if (!additionalSalesAvailable
                && !employee.path("additionalSalesPerformance").isNull()) {
            context.add("UNAVAILABLE_EMPLOYEE_SECTION",
                    path + ".additionalSalesPerformance", facts.employeeRef());
        }
    }

    private void validateEmployeeActions(
            JsonNode employee,
            String path,
            String employeeRef,
            ValidationContext context
    ) {
        JsonNode actions = employee.path("recommendedActions");
        for (int index = 0; index < actions.size(); index++) {
            JsonNode action = actions.get(index);
            String actionPath = path + ".recommendedActions[" + index + "]";
            JsonNode targets = action.path("targetEmployeeRefs");
            boolean onlyCurrent = targets.size() == 1
                    && employeeRef.equals(targets.get(0).asText());
            if (!"EMPLOYEE".equals(action.path("targetScope").asText())
                    || !onlyCurrent) {
                context.add("EMPLOYEE_ACTION_TARGET_MISMATCH", actionPath, employeeRef);
            }
        }
    }

    private void validateReferences(
            JsonNode node,
            String path,
            String employeeContext,
            ValidationContext context
    ) {
        if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                validateReferences(
                        node.get(index), path + "[" + index + "]",
                        employeeContext, context
                );
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        validateActionTarget(node, path, context);
        for (String field : node.propertyNames()) {
            JsonNode value = node.get(field);
            String childPath = path + "." + field;
            if ("dataLimitations".equals(field)) {
                continue;
            }
            if ("evidenceRefs".equals(field)) {
                validateEvidence(value, childPath, employeeContext, context);
            } else if ("candidateRef".equals(field) && !value.isNull()) {
                requireMember(value.asText(), context.candidateRefs(),
                        "UNKNOWN_CANDIDATE_REF", childPath, context);
            } else if ("employeeRef".equals(field) && !value.isNull()) {
                requireMember(value.asText(), context.employeeRefs(),
                        "UNKNOWN_EMPLOYEE_REF", childPath, context);
            } else if (field.endsWith("EmployeeRefs") || "employeeRefs".equals(field)) {
                validateArrayMembers(value, context.employeeRefs(),
                        "UNKNOWN_EMPLOYEE_REF", childPath, context);
            } else if ("categoryCode".equals(field) && !value.isNull()) {
                requireMember(value.asText(), context.categoryCodes(),
                        "UNKNOWN_CATEGORY_CODE", childPath, context);
            } else if ("competencyCode".equals(field)) {
                validateCompetency(value.asText(), childPath, context);
            }
            validateReferences(value, childPath, employeeContext, context);
        }
    }

    private void validateActionTarget(
            JsonNode node,
            String path,
            ValidationContext context
    ) {
        JsonNode scope = node.get("targetScope");
        JsonNode targets = node.get("targetEmployeeRefs");
        if (scope == null || targets == null) {
            return;
        }
        boolean valid = "EMPLOYEE".equals(scope.asText())
                ? !targets.isEmpty()
                : targets.isEmpty();
        if (!valid) {
            context.add("ACTION_TARGET_SCOPE_MISMATCH", path, scope.asText());
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
                context.add("UNAVAILABLE_EVIDENCE_REF", path + "[" + index + "]", reference);
            } else if (employeeContext != null
                    && evidence.employeeRef() != null
                    && !employeeContext.equals(evidence.employeeRef())) {
                context.add("CROSS_EMPLOYEE_EVIDENCE", path + "[" + index + "]", reference);
            } else if (employeeContext != null
                    && employeeContext.equals(evidence.employeeRef())) {
                hasEmployeeEvidence = true;
            }
        }
        if (employeeContext != null && !hasEmployeeEvidence) {
            context.add("EMPLOYEE_EVIDENCE_REQUIRED", path, employeeContext);
        }
    }

    private void validateCompetency(
            String value,
            String path,
            ValidationContext context
    ) {
        boolean allowed = context.competencyCodes().contains(value);
        if (value.startsWith("CATEGORY:")) {
            allowed = context.categoryCodes().contains(value.substring("CATEGORY:".length()));
        }
        if (!allowed) {
            context.add("UNKNOWN_COMPETENCY_CODE", path, value);
        }
    }

    private void validateLimitations(JsonNode root, ValidationContext context) {
        Set<LimitationKey> expectedRoot = new HashSet<>();
        context.input().manifest().limitations().stream()
                .filter(value -> value.employeeRef() == null)
                .map(LimitationKey::from)
                .forEach(expectedRoot::add);
        Set<LimitationKey> actualRoot = limitationSet(
                root.path("dataLimitations"), "$.dataLimitations", context
        );
        if (!actualRoot.equals(expectedRoot)) {
            context.add("LIMITATION_SET_MISMATCH", "$.dataLimitations", null);
        }
        JsonNode employees = root.path("employees");
        for (int index = 0; index < employees.size(); index++) {
            JsonNode employee = employees.get(index);
            String employeeRef = employee.path("employeeRef").asText();
            String path = "$.employees[" + index + "].dataLimitations";
            Set<LimitationKey> expected = new HashSet<>();
            context.input().manifest().limitations().stream()
                    .filter(value -> employeeRef.equals(value.employeeRef()))
                    .map(LimitationKey::from)
                    .forEach(expected::add);
            if (!limitationSet(employee.path("dataLimitations"), path, context)
                    .equals(expected)) {
                context.add("LIMITATION_SET_MISMATCH", path, employeeRef);
            }
        }
    }

    private Set<LimitationKey> limitationSet(
            JsonNode limitations,
            String path,
            ValidationContext context
    ) {
        Set<LimitationKey> result = new HashSet<>();
        int index = 0;
        for (JsonNode limitation : limitations) {
            LimitationKey key = LimitationKey.from(limitation);
            if (!result.add(key)) {
                context.add("DUPLICATE_LIMITATION", path + "[" + index + "]", key.code());
            }
            index++;
        }
        return result;
    }

    private void validateTeamRelationships(
            JsonNode team,
            ValidationContext context
    ) {
        Map<String, Set<String>> leaders = new HashMap<>();
        for (JsonNode leader : team.path("competencyLeaders")) {
            leaders.computeIfAbsent(
                    leader.path("competencyCode").asText(),
                    key -> new HashSet<>()
            ).addAll(stringValues(leader.path("employeeRefs")));
        }
        JsonNode opportunities = team.path("learningOpportunities");
        for (int index = 0; index < opportunities.size(); index++) {
            JsonNode opportunity = opportunities.get(index);
            String path = "$.teamInsights.learningOpportunities[" + index + "]";
            Set<String> mentors = stringValues(
                    opportunity.path("mentorEmployeeRefs")
            );
            Set<String> targets = stringValues(
                    opportunity.path("targetEmployeeRefs")
            );
            Set<String> confirmed = leaders.getOrDefault(
                    opportunity.path("competencyCode").asText(),
                    Set.of()
            );
            if (!confirmed.containsAll(mentors)) {
                context.add("MENTOR_NOT_COMPETENCY_LEADER", path, null);
            }
            if (mentors.stream().anyMatch(targets::contains)) {
                context.add("MENTOR_TARGET_OVERLAP", path, null);
            }
        }
    }

    private void validateRecommendedActionUniqueness(
            JsonNode node,
            String path,
            ValidationContext context
    ) {
        if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                validateRecommendedActionUniqueness(
                        node.get(index), path + "[" + index + "]", context
                );
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        JsonNode actions = node.get("recommendedActions");
        if (actions != null && actions.isArray()) {
            Set<ActionKey> seen = new HashSet<>();
            for (int index = 0; index < actions.size(); index++) {
                JsonNode action = actions.get(index);
                ActionKey key = new ActionKey(
                        action.path("type").asText(),
                        action.path("horizon").asText(),
                        action.path("targetScope").asText(),
                        stringValues(action.path("targetEmployeeRefs")),
                        stringValues(action.path("evidenceRefs"))
                );
                if (!seen.add(key)) {
                    context.add(
                            "DUPLICATE_RECOMMENDED_ACTION",
                            path + ".recommendedActions[" + index + "]",
                            null
                    );
                }
            }
        }
        for (String field : node.propertyNames()) {
            if (!"recommendedActions".equals(field)) {
                validateRecommendedActionUniqueness(
                        node.get(field), path + "." + field, context
                );
            }
        }
    }

    private void validateRiskEvidenceDimensions(
            JsonNode node,
            String path,
            ValidationContext context
    ) {
        if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                validateRiskEvidenceDimensions(
                        node.get(index), path + "[" + index + "]", context
                );
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        if ("RISK".equals(node.path("kind").asText())) {
            String narrative = node.path("title").asText() + " "
                    + node.path("summary").asText();
            Set<String> evidenceRefs = stringValues(node.path("evidenceRefs"));
            if (REVENUE_NARRATIVE.matcher(narrative).find()
                    && evidenceRefs.stream().noneMatch(
                    WeeklyInterpretationResponseValidator::isRevenueEvidence
            )) {
                context.add("UNSUPPORTED_RISK_DIMENSION", path, "REVENUE");
            }
            if (PROFITABILITY_NARRATIVE.matcher(narrative).find()
                    && evidenceRefs.stream().noneMatch(
                    WeeklyInterpretationResponseValidator::isProfitabilityEvidence
            )) {
                context.add("UNSUPPORTED_RISK_DIMENSION", path, "PROFITABILITY");
            }
        }
        for (String field : node.propertyNames()) {
            validateRiskEvidenceDimensions(
                    node.get(field), path + "." + field, context
            );
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

    private void validateDuplicateNarrative(
            JsonNode scope,
            String path,
            ValidationContext context
    ) {
        // Exact narrative repetition is a soft quality signal, not a publication blocker.
    }

    private void collectNarrative(
            JsonNode node,
            String path,
            Map<NarrativeKey, String> seen,
            ValidationContext context
    ) {
        if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                collectNarrative(node.get(index), path + "[" + index + "]", seen, context);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        Set<String> evidenceRefs = stringValues(node.path("evidenceRefs"));
        for (String field : NARRATIVE_FIELDS) {
            JsonNode narrative = node.get(field);
            if (narrative != null && narrative.isTextual() && !evidenceRefs.isEmpty()) {
                NarrativeKey key = new NarrativeKey(narrative.asText(), evidenceRefs);
                String firstPath = seen.putIfAbsent(key, path + "." + field);
                if (firstPath != null) {
                    context.add("DUPLICATE_NARRATIVE", path + "." + field, firstPath);
                }
            }
        }
        for (String field : node.propertyNames()) {
            collectNarrative(node.get(field), path + "." + field, seen, context);
        }
    }

    private Set<String> stringValues(JsonNode values) {
        Set<String> result = new HashSet<>();
        if (values.isArray()) {
            values.forEach(value -> result.add(value.asText()));
        }
        return result;
    }

    private void validateNarrative(
            JsonNode node,
            String path,
            ValidationContext context
    ) {
        if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                validateNarrative(node.get(index), path + "[" + index + "]", context);
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
            String narrative = value.isTextual() ? value.asText() : "";
            for (String employeeRef : context.employeeRefs()) {
                narrative = narrative.replace(employeeRef, "");
            }
            if (NARRATIVE_FIELDS.contains(field)
                    && value.isTextual()
                    && FORBIDDEN_NARRATIVE_LITERAL.matcher(narrative).find()) {
                context.add("FORBIDDEN_NARRATIVE_LITERAL", childPath, null);
            }
            validateNarrative(value, childPath, context);
        }
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            var result = objectMapper.createObjectNode();
            node.propertyNames().stream().sorted()
                    .forEach(name -> result.set(name, canonicalize(node.get(name))));
            return result;
        }
        if (node.isArray()) {
            var result = objectMapper.createArrayNode();
            node.forEach(value -> result.add(canonicalize(value)));
            return result;
        }
        return node.deepCopy();
    }

    private void validateArrayMembers(
            JsonNode values,
            Set<String> allowed,
            String code,
            String path,
            ValidationContext context
    ) {
        for (int index = 0; index < values.size(); index++) {
            requireMember(
                    values.get(index).asText(), allowed, code,
                    path + "[" + index + "]", context
            );
        }
    }

    private void requireMember(
            String value,
            Set<String> allowed,
            String code,
            String path,
            ValidationContext context
    ) {
        if (!allowed.contains(value)) {
            context.add(code, path, value);
        }
    }

    private static String emptyToRoot(String value) {
        return value == null || value.isBlank() ? "$" : value;
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

    private record NarrativeKey(
            String text,
            Set<String> evidenceRefs
    ) {

        private NarrativeKey {
            evidenceRefs = Set.copyOf(evidenceRefs);
        }
    }

    private record LimitationKey(
            String code,
            String scope,
            String employeeRef,
            String categoryCode,
            String impact,
            Set<String> affectedSections,
            Set<String> evidenceRefs
    ) {

        private static LimitationKey from(Limitation value) {
            return new LimitationKey(
                    value.code(), value.scope().name(), value.employeeRef(),
                    value.categoryCode(), value.impact().name(),
                    canonicalSections(value.affectedSections()),
                    Set.copyOf(value.evidenceRefs())
            );
        }

        private static LimitationKey from(JsonNode value) {
            return new LimitationKey(
                    value.path("code").asText(), value.path("scope").asText(),
                    nullableText(value.path("employeeRef")),
                    nullableText(value.path("categoryCode")),
                    value.path("impact").asText(), stringSet(value.path("affectedSections")),
                    stringSet(value.path("evidenceRefs"))
            );
        }

        private static Set<String> stringSet(JsonNode values) {
            Set<String> result = new HashSet<>();
            values.forEach(value -> result.add(value.asText()));
            return result;
        }

        private static String nullableText(JsonNode value) {
            return value.isNull() ? null : value.asText();
        }
    }

    private static final class ValidationContext {

        private final WeeklyInterpretationInput input;
        private final Set<String> employeeRefs;
        private final Map<String, WeeklyInterpretationInput.EmployeeFacts> employeeFacts;
        private final Map<String, EvidenceIndexEntry> evidence;
        private final Set<String> candidateRefs;
        private final Set<String> categoryCodes;
        private final Set<String> competencyCodes;
        private final List<LlmValidationViolation> violations = new ArrayList<>();

        private ValidationContext(WeeklyInterpretationInput input) {
            this.input = input;
            this.employeeRefs = Set.copyOf(input.manifest().employeeRefs());
            this.employeeFacts = new HashMap<>();
            input.facts().employees().forEach(value -> employeeFacts.put(
                    value.employeeRef(), value
            ));
            this.evidence = new HashMap<>();
            input.facts().store().forEach(value -> addFactEvidence(
                    value, WeeklyInterpretationInput.Scope.STORE, null
            ));
            input.facts().team().forEach(value -> addFactEvidence(
                    value, WeeklyInterpretationInput.Scope.TEAM, null
            ));
            input.facts().employees().forEach(employee -> employee.facts()
                    .forEach(value -> addFactEvidence(
                            value, WeeklyInterpretationInput.Scope.EMPLOYEE,
                            employee.employeeRef()
                    )));
            input.manifest().evidence().forEach(value -> evidence.put(
                    value.evidenceRef(), value
            ));
            this.candidateRefs = Set.copyOf(input.manifest().candidateRefs());
            this.categoryCodes = Set.copyOf(input.manifest().categoryCodes());
            this.competencyCodes = Set.copyOf(input.manifest().competencyCodes());
        }

        private void add(String code, String path, String reference) {
            if (violations.size() < MAX_VIOLATIONS) {
                violations.add(new LlmValidationViolation(code, path, reference));
            }
        }

        private WeeklyInterpretationInput input() {
            return input;
        }

        private void addFactEvidence(
                WeeklyInterpretationInput.Fact fact,
                WeeklyInterpretationInput.Scope scope,
                String employeeRef
        ) {
            evidence.putIfAbsent(
                    fact.evidenceRef(),
                    new EvidenceIndexEntry(
                            fact.evidenceRef(), scope, employeeRef, true
                    )
            );
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

        private List<LlmValidationViolation> violations() {
            return List.copyOf(violations);
        }
    }
}
