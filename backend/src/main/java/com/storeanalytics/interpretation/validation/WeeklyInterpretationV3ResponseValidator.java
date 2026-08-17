package com.storeanalytics.interpretation.validation;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.interpretation.contract.LlmContractResources;
import com.storeanalytics.interpretation.contract.WeeklyCandidateDisplayPolicy;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput;
import com.storeanalytics.interpretation.contract.WeeklyPrimarySignalPolicy;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.CandidateSignal;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.EvidenceIndexEntry;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Validates the primary-signal WeeklyInterpretationContent v3 contract. */
@Component
public final class WeeklyInterpretationV3ResponseValidator
        extends WeeklyInterpretationV2ResponseValidator {
    private static final ObjectMapper PROVIDER_OUTPUT_MAPPER =
            JsonMapper.builder().build();

    public LlmResponseValidationResult validatePrivacyReduced(
            WeeklyInterpretationInput providerInput,
            WeeklyInterpretationInput snapshotInput,
            String responseBody
    ) {
        WeeklyInterpretationInput provider = requireNonNull(
                providerInput, "providerInput"
        );
        WeeklyInterpretationInput snapshot = requireNonNull(
                snapshotInput, "snapshotInput"
        );
        String body = requireNonNull(responseBody, "responseBody");
        List<LlmValidationViolation> violations =
                validatePrivacyReducedProviderOutput(provider, body);
        WeeklyInterpretationInput validationInput =
                privacyReducedValidationInput(provider, snapshot);
        if (violations == null) {
            return validate(validationInput, body);
        }
        if (!violations.isEmpty()) {
            return LlmResponseValidationResult.invalid(
                    LlmValidationOutcome.SEMANTIC_INVALID,
                    violations
            );
        }
        return validate(validationInput, body);
    }

    private WeeklyInterpretationInput privacyReducedValidationInput(
            WeeklyInterpretationInput provider,
            WeeklyInterpretationInput snapshot
    ) {
        require(
                provider.contractVersion() == snapshot.contractVersion()
                        && provider.snapshot().equals(snapshot.snapshot()),
                "Provider input and snapshot input must describe one snapshot"
        );
        List<CandidateSignal> candidates = new ArrayList<>(
                provider.facts().candidateSignals()
        );
        Set<String> references = new HashSet<>();
        candidates.forEach(candidate ->
                references.add(candidate.candidateRef()));
        snapshot.facts().candidateSignals().stream()
                .filter(this::isBackendOwnedCandidate)
                .filter(candidate -> references.add(
                        candidate.candidateRef()
                ))
                .forEach(candidates::add);
        WeeklyInterpretationInput.Manifest source = snapshot.manifest();
        WeeklyInterpretationInput.Manifest manifest =
                new WeeklyInterpretationInput.Manifest(
                        source.employeeRefs(),
                        source.evidence(),
                        candidates.stream()
                                .map(CandidateSignal::candidateRef)
                                .toList(),
                        source.categoryCodes(),
                        source.categoryLabels(),
                        source.competencyCodes(),
                        source.limitations()
                );
        WeeklyInterpretationInput.Facts facts =
                new WeeklyInterpretationInput.Facts(
                        snapshot.facts().store(),
                        snapshot.facts().team(),
                        snapshot.facts().employees(),
                        candidates
                );
        return new WeeklyInterpretationInput(
                snapshot.contractVersion(),
                snapshot.snapshot(),
                manifest,
                facts
        );
    }

    private boolean isBackendOwnedCandidate(CandidateSignal candidate) {
        return candidate.employeeRef() != null
                || !candidate.targetEmployeeRefs().isEmpty()
                || isRelationshipTheme(candidate.theme());
    }

    private List<LlmValidationViolation>
            validatePrivacyReducedProviderOutput(
                    WeeklyInterpretationInput provider,
                    String responseBody
            ) {
        JsonNode root;
        try {
            root = PROVIDER_OUTPUT_MAPPER.readTree(responseBody);
        } catch (JacksonException exception) {
            return null;
        }
        if (!(root instanceof ObjectNode object)) {
            return List.of();
        }
        List<LlmValidationViolation> violations = new ArrayList<>();
        for (String field : List.of(
                "employees",
                "employeeHeadlines",
                "summaryBlocks",
                "dataLimitations"
        )) {
            if (object.has(field)) {
                violations.add(new LlmValidationViolation(
                        "PROVIDER_BACKEND_FIELD_PRESENT",
                        "/" + field,
                        null
                ));
            }
        }
        if (object.path("teamRelationships").size() > 0) {
            violations.add(new LlmValidationViolation(
                    "PROVIDER_RELATIONSHIP_NOT_ALLOWED",
                    "/teamRelationships",
                    null
            ));
        }
        Set<String> candidateRefs = Set.copyOf(
                provider.manifest().candidateRefs()
        );
        Set<String> evidenceRefs = provider.manifest().evidence().stream()
                .filter(EvidenceIndexEntry::available)
                .map(EvidenceIndexEntry::evidenceRef)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> categoryCodes = Set.copyOf(
                provider.manifest().categoryCodes()
        );
        validateProviderOwnedReferences(
                object,
                "",
                candidateRefs,
                evidenceRefs,
                categoryCodes,
                violations
        );
        return violations;
    }

    private void validateProviderOwnedReferences(
            JsonNode node,
            String path,
            Set<String> candidateRefs,
            Set<String> evidenceRefs,
            Set<String> categoryCodes,
            List<LlmValidationViolation> violations
    ) {
        if (node instanceof ObjectNode object) {
            object.properties().forEach(entry -> {
                String field = entry.getKey();
                JsonNode value = entry.getValue();
                String childPath = path + "/" + field;
                if ("candidateRef".equals(field)
                        && value.isTextual()
                        && !candidateRefs.contains(value.asText())) {
                    violations.add(new LlmValidationViolation(
                            "PROVIDER_CANDIDATE_NOT_SENT",
                            childPath,
                            null
                    ));
                } else if ("categoryCode".equals(field)
                        && value.isTextual()
                        && !categoryCodes.contains(value.asText())) {
                    violations.add(new LlmValidationViolation(
                            "PROVIDER_CATEGORY_NOT_SENT",
                            childPath,
                            null
                    ));
                } else if ("employeeRef".equals(field)
                        && !value.isNull()) {
                    violations.add(new LlmValidationViolation(
                            "PROVIDER_EMPLOYEE_REFERENCE_NOT_ALLOWED",
                            childPath,
                            null
                    ));
                } else if (("sourceEmployeeRefs".equals(field)
                        || "targetEmployeeRefs".equals(field))
                        && value.size() > 0) {
                    violations.add(new LlmValidationViolation(
                            "PROVIDER_EMPLOYEE_REFERENCE_NOT_ALLOWED",
                            childPath,
                            null
                    ));
                } else if ("evidenceRefs".equals(field)
                        && value.isArray()) {
                    for (int index = 0; index < value.size(); index++) {
                        JsonNode reference = value.get(index);
                        if (reference.isTextual()
                                && !evidenceRefs.contains(
                                reference.asText()
                        )) {
                            violations.add(new LlmValidationViolation(
                                    "PROVIDER_EVIDENCE_NOT_SENT",
                                    childPath + "/" + index,
                                    null
                            ));
                        }
                    }
                }
                validateProviderOwnedReferences(
                        value,
                        childPath,
                        candidateRefs,
                        evidenceRefs,
                        categoryCodes,
                        violations
                );
            });
            return;
        }
        if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                validateProviderOwnedReferences(
                        node.get(index),
                        path + "/" + index,
                        candidateRefs,
                        evidenceRefs,
                        categoryCodes,
                        violations
                );
            }
        }
    }

    public WeeklyInterpretationV3ResponseValidator() {
        super(
                LlmContractResources.PRIMARY_SIGNAL_CONTENT_SCHEMA,
                LlmContractResources.PRIMARY_SIGNAL_CONTENT_SCHEMA_VERSION
        );
    }

    @Override
    protected void normalizeVersionSpecificFields(
            ObjectNode root,
            WeeklyInterpretationInput input
    ) {
        if (normalizeStructuredSummaryTransport(root, input)) {
            normalizeTeamRelationships(root, input);
            normalizeCandidateNarratives(root, input);
        }
        if (!(root.path("primarySignal") instanceof ObjectNode primarySignal)) {
            return;
        }
        putNullIfMissing(primarySignal, "employeeRef");
        putNullIfMissing(primarySignal, "categoryCode");
        Map<String, CandidateSignal> candidates = new HashMap<>();
        input.facts().candidateSignals().forEach(candidate ->
                candidates.put(candidate.candidateRef(), candidate)
        );
        CandidateSignal candidate = candidates.get(
                nullableText(primarySignal.path("candidateRef"))
        );
        if (candidate == null) {
            return;
        }
        Map<String, EvidenceIndexEntry> evidence = evidence(input);
        primarySignal.put("kind", candidate.kind().name());
        primarySignal.put("theme", candidate.theme());
        nullable(primarySignal, "employeeRef", candidate.employeeRef());
        nullable(primarySignal, "categoryCode", candidate.categoryCode());
        primarySignal.put("scope", candidateScope(candidate, evidence));
        var exactEvidence = primarySignal.putArray("evidenceRefs");
        WeeklyCandidateDisplayPolicy.evidenceRefs(candidate, input)
                .forEach(exactEvidence::add);
    }

    private boolean normalizeStructuredSummaryTransport(
            ObjectNode root,
            WeeklyInterpretationInput input
    ) {
        if (root.path("backendEmployeeHeadlines").asBoolean(false)) {
            materializeBackendEmployeeHeadlines(root, input);
        }
        root.remove("backendEmployeeHeadlines");
        boolean structuredTransport = root.has("teamOverview")
                || root.has("employeeHeadlines")
                || root.has("supportingSummaries");
        if (!structuredTransport) {
            return false;
        }
        normalizeBackendTeamOverview(root, input);

        Map<String, WeeklyInterpretationInput.Sufficiency> statuses =
                new HashMap<>();
        input.facts().employees().forEach(employee ->
                statuses.put(employee.employeeRef(), employee.analysisStatus())
        );
        ArrayNode employees = root.putArray("employees");
        for (String employeeRef : input.manifest().employeeRefs()) {
            WeeklyInterpretationInput.Sufficiency status =
                    statuses.get(employeeRef);
            if (status != null) {
                ObjectNode employee = employees.addObject();
                employee.put("employeeRef", employeeRef);
                employee.put("analysisStatus", status.name());
            }
        }

        ArrayNode summaries = root.putArray("summaryBlocks");
        appendBackendStoreSummary(summaries, input);
        appendFixedSummary(
                summaries,
                root.path("teamOverview"),
                "TEAM",
                null,
                "TEAM_OVERVIEW"
        );
        JsonNode employeeHeadlines = root.path("employeeHeadlines");
        for (String employeeRef : input.manifest().employeeRefs()) {
            appendFixedSummary(
                    summaries,
                    employeeHeadlines.path(employeeRef),
                    "EMPLOYEE",
                    employeeRef,
                    "HEADLINE"
            );
        }
        for (JsonNode summary : root.path("supportingSummaries")) {
            summaries.add(summary.deepCopy());
        }

        root.remove("teamOverview");
        root.remove("employeeHeadlines");
        root.remove("supportingSummaries");
        return true;
    }

    private void normalizeBackendTeamOverview(
            ObjectNode root,
            WeeklyInterpretationInput input
    ) {
        if (!(root.path("teamOverview") instanceof ObjectNode overview)) {
            return;
        }
        List<WeeklyInterpretationInput.Fact> ratingFacts = input.facts()
                .employees().stream()
                .flatMap(employee -> employee.facts().stream())
                .filter(fact -> "RATING_STRUCTURE_SCORE".equals(
                        fact.metricCode()
                ))
                .toList();
        boolean tie = ratingFacts.size() >= 2
                && ratingFacts.stream()
                .map(WeeklyInterpretationInput.Fact::value)
                .map(this::number)
                .distinct()
                .count() == 1;
        if (tie) {
            overview.put(
                    "text",
                    "Результаты сотрудников по доступной компетенции равны."
            );
            ArrayNode evidence = overview.putArray("evidenceRefs");
            ratingFacts.stream()
                    .map(WeeklyInterpretationInput.Fact::evidenceRef)
                    .forEach(evidence::add);
            return;
        }
        input.facts().team().stream()
                .filter(fact -> "RATING_ELIGIBLE_COUNT".equals(
                        fact.metricCode()
                ))
                .findFirst()
                .ifPresent(fact -> {
                    overview.put("text", teamOverviewText(input));
                    overview.putArray("evidenceRefs").add(
                            fact.evidenceRef()
                    );
                });
    }

    private String teamOverviewText(WeeklyInterpretationInput input) {
        boolean comparable = input.facts().team().stream()
                .filter(fact -> "RATING_ELIGIBLE_COUNT".equals(
                        fact.metricCode()
                ))
                .map(WeeklyInterpretationInput.Fact::value)
                .map(this::number)
                .anyMatch(value -> value.compareTo(new BigDecimal("2")) >= 0);
        return comparable
                ? "Командные данные позволяют сопоставить сотрудников."
                : "Сопоставление сотрудников ограничено недостаточной "
                        + "командной базой.";
    }

    private void appendBackendStoreSummary(
            ArrayNode summaries,
            WeeklyInterpretationInput input
    ) {
        if (!WeeklyPrimarySignalPolicy.orderedStoreCandidates(input).isEmpty()) {
            return;
        }
        BackendNarrative narrative = backendStoreNarrative(input);
        if (narrative == null) {
            return;
        }
        ObjectNode summary = summaries.addObject();
        summary.put("scope", "STORE");
        summary.putNull("employeeRef");
        summary.put("section", "RESULT");
        summary.putNull("categoryCode");
        summary.put("text", narrative.text());
        summary.putArray("evidenceRefs").add(narrative.evidenceRef());
    }

    private BackendNarrative backendStoreNarrative(
            WeeklyInterpretationInput input
    ) {
        List<WeeklyInterpretationInput.Fact> facts = input.facts().store();
        for (WeeklyInterpretationInput.Fact fact : facts) {
            if ("PLAN_PROJECTED_COMPLETION_PERCENT".equals(
                    fact.metricCode()
            )) {
                int comparison = number(fact.value()).compareTo(
                        new BigDecimal("100")
                );
                String text = comparison == 0
                        ? "План выполнен на целевом уровне."
                        : comparison > 0
                        ? "Выполнение плана выше целевого уровня."
                        : "Выполнение плана ниже целевого уровня.";
                return new BackendNarrative(text, fact.evidenceRef());
            }
        }
        for (WeeklyInterpretationInput.Fact fact : facts) {
            boolean smallDenominator = fact.metricCode().startsWith(
                    "DENOMINATOR_"
            ) && number(fact.value()).compareTo(new BigDecimal("5")) < 0;
            if (fact.evidenceRef().contains(".ATTACH:")
                    && (fact.sufficiency()
                    != WeeklyInterpretationInput.Sufficiency.SUFFICIENT
                    || smallDenominator)) {
                return new BackendNarrative(
                        "База продаж недостаточна для надёжной оценки "
                                + "частоты дополнительных продаж.",
                        fact.evidenceRef()
                );
            }
        }
        for (WeeklyInterpretationInput.Fact fact : facts) {
            if (fact.evidenceRef().contains(".CATEGORY:")
                    && fact.comparison() != null
                    && fact.comparison().previousValue() != null
                    && BigDecimal.ZERO.compareTo(
                    fact.comparison().previousValue()
            ) == 0
                    && number(fact.value()).compareTo(BigDecimal.ZERO) > 0) {
                return new BackendNarrative(
                        "Выручка категории появилась после нулевого "
                                + "значения прошлого периода.",
                        fact.evidenceRef()
                );
            }
        }
        for (WeeklyInterpretationInput.Fact fact : facts) {
            if ("NET_REVENUE".equals(fact.metricCode())
                    && fact.comparison() != null
                    && fact.comparison().absoluteDelta() != null
                    && BigDecimal.ZERO.compareTo(
                    fact.comparison().absoluteDelta()
            ) == 0) {
                return new BackendNarrative(
                        "Выручка магазина существенно не изменилась "
                                + "относительно прошлого периода.",
                        fact.evidenceRef()
                );
            }
        }
        if (facts.isEmpty()) {
            return null;
        }
        return new BackendNarrative(
                "По магазину нет отдельного существенного изменения за период.",
                facts.get(0).evidenceRef()
        );
    }

    private BigDecimal number(Object value) {
        return value instanceof BigDecimal decimal
                ? decimal : new BigDecimal(value.toString());
    }

    private record BackendNarrative(String text, String evidenceRef) {
    }

    private void materializeBackendEmployeeHeadlines(
            ObjectNode root,
            WeeklyInterpretationInput input
    ) {
        ObjectNode headlines = root.putObject("employeeHeadlines");
        Map<String, WeeklyInterpretationInput.EmployeeFacts> employees =
                new HashMap<>();
        input.facts().employees().forEach(employee ->
                employees.put(employee.employeeRef(), employee)
        );
        for (String employeeRef : input.manifest().employeeRefs()) {
            WeeklyInterpretationInput.EmployeeFacts employee =
                    employees.get(employeeRef);
            if (employee == null || employee.facts().isEmpty()) {
                continue;
            }
            Set<String> employeeEvidence = employee.facts().stream()
                    .map(WeeklyInterpretationInput.Fact::evidenceRef)
                    .collect(java.util.stream.Collectors.toSet());
            CandidateSignal candidate = input.facts().candidateSignals()
                    .stream()
                    .filter(value -> employeeRef.equals(value.employeeRef()))
                    .filter(value -> !isRelationshipTheme(value.theme()))
                    .filter(value -> employeeEvidence.containsAll(
                            value.evidenceRefs()
                    ))
                    .findFirst()
                    .orElse(null);
            ObjectNode headline = headlines.putObject(employeeRef);
            if (employee.analysisStatus()
                    == WeeklyInterpretationInput.Sufficiency.INSUFFICIENT) {
                headline.put(
                        "text",
                        "Данных недостаточно для персонального анализа "
                                + "сотрудника."
                );
                appendHeadlineEvidence(
                        headline,
                        employee.facts().stream()
                                .filter(fact -> "WORKLOAD_STATUS".equals(
                                        fact.metricCode()
                                ))
                                .findFirst()
                                .orElse(employee.facts().get(0))
                                .evidenceRef()
                );
            } else if (candidate != null) {
                headline.put(
                        "text",
                        WeeklyCandidateDisplayPolicy
                                .forCandidate(candidate, input).summary()
                );
                ArrayNode evidence = headline.putArray("evidenceRefs");
                WeeklyCandidateDisplayPolicy
                        .evidenceRefs(candidate, input)
                        .forEach(evidence::add);
            } else {
                BackendNarrative narrative = employeeNeutralNarrative(
                        employee
                );
                headline.put("text", narrative.text());
                appendHeadlineEvidence(headline, narrative.evidenceRef());
            }
        }
    }

    private BackendNarrative employeeNeutralNarrative(
            WeeklyInterpretationInput.EmployeeFacts employee
    ) {
        if (employee.analysisStatus()
                == WeeklyInterpretationInput.Sufficiency.LIMITED) {
            WeeklyInterpretationInput.Fact fact = employee.facts().stream()
                    .filter(value -> !"WORKLOAD_STATUS".equals(
                            value.metricCode()
                    ))
                    .findFirst()
                    .orElse(employee.facts().get(0));
            return new BackendNarrative(
                    "По сотруднику доступен только ограниченный текущий "
                            + "результат.",
                    fact.evidenceRef()
            );
        }
        for (WeeklyInterpretationInput.Fact fact : employee.facts()) {
            if ("NET_REVENUE".equals(fact.metricCode())
                    && fact.comparison() != null
                    && fact.comparison().absoluteDelta() != null
                    && fact.comparison().absoluteDelta()
                    .compareTo(BigDecimal.ZERO) == 0) {
                return new BackendNarrative(
                        "Выручка сотрудника существенно не изменилась "
                                + "относительно прошлого периода.",
                        fact.evidenceRef()
                );
            }
        }
        return new BackendNarrative(
                "По сотруднику нет отдельного существенного изменения "
                        + "за период.",
                employee.facts().get(0).evidenceRef()
        );
    }

    private void appendHeadlineEvidence(
            ObjectNode headline,
            String evidenceRef
    ) {
        headline.putArray("evidenceRefs").add(evidenceRef);
    }

    private void normalizeTeamRelationships(
            ObjectNode root,
            WeeklyInterpretationInput input
    ) {
        ArrayNode relationships = root.putArray("teamRelationships");
        input.facts().candidateSignals().stream()
                .filter(candidate -> isRelationshipTheme(candidate.theme()))
                .forEach(candidate -> {
                    ObjectNode relationship = relationships.addObject();
                    relationship.put("type", candidate.theme());
                    nullable(
                            relationship,
                            "competencyCode",
                            candidate.competencyCode()
                    );
                    ArrayNode sources = relationship.putArray(
                            "sourceEmployeeRefs"
                    );
                    if (candidate.employeeRef() != null) {
                        sources.add(candidate.employeeRef());
                    }
                    ArrayNode targets = relationship.putArray(
                            "targetEmployeeRefs"
                    );
                    candidate.targetEmployeeRefs().forEach(targets::add);
                    relationship.put(
                            "summary",
                            relationshipSummary(candidate.theme())
                    );
                    ArrayNode evidence = relationship.putArray("evidenceRefs");
                    candidate.evidenceRefs().forEach(evidence::add);
                });
    }

    private void normalizeCandidateNarratives(
            ObjectNode root,
            WeeklyInterpretationInput input
    ) {
        Map<String, CandidateSignal> candidates = new HashMap<>();
        input.facts().candidateSignals().forEach(candidate ->
                candidates.put(candidate.candidateRef(), candidate)
        );
        if (root.path("primarySignal") instanceof ObjectNode primary) {
            CandidateSignal candidate = candidates.get(
                    nullableText(primary.path("candidateRef"))
            );
            if (candidate != null) {
                primary.put(
                        "text",
                        WeeklyCandidateDisplayPolicy
                                .forCandidate(candidate, input).summary()
                );
            }
        }
        for (JsonNode value : root.path("insights")) {
            if (!(value instanceof ObjectNode insight)) {
                continue;
            }
            CandidateSignal candidate = candidates.get(
                    nullableText(insight.path("candidateRef"))
            );
            if (candidate == null) {
                continue;
            }
            WeeklyCandidateDisplayPolicy.Narrative narrative =
                    WeeklyCandidateDisplayPolicy.forCandidate(
                            candidate, input
                    );
            insight.put("title", narrative.title());
            insight.put("summary", narrative.summary());
            ArrayNode evidence = insight.putArray("evidenceRefs");
            WeeklyCandidateDisplayPolicy.evidenceRefs(candidate, input)
                    .forEach(evidence::add);
        }
    }

    private boolean isRelationshipTheme(String theme) {
        return "COMPETENCY_LEADER".equals(theme)
                || "MOST_IMPROVED".equals(theme)
                || "LEARNING_OPPORTUNITY".equals(theme);
    }

    private String relationshipSummary(String theme) {
        return switch (theme) {
            case "COMPETENCY_LEADER" ->
                    "В команде подтверждён лидер по соответствующей "
                            + "компетенции.";
            case "MOST_IMPROVED" ->
                    "Подтверждена наиболее заметная положительная динамика "
                            + "среди сопоставимых сотрудников.";
            case "LEARNING_OPPORTUNITY" ->
                    "Подтверждена возможность обмена практикой по "
                            + "соответствующей компетенции.";
            default -> throw new IllegalArgumentException(
                    "Unsupported relationship theme: " + theme
            );
        };
    }

    private void appendFixedSummary(
            ArrayNode summaries,
            JsonNode source,
            String scope,
            String employeeRef,
            String section
    ) {
        if (!source.isObject()) {
            return;
        }
        ObjectNode summary = summaries.addObject();
        summary.put("scope", scope);
        nullable(summary, "employeeRef", employeeRef);
        summary.put("section", section);
        summary.putNull("categoryCode");
        summary.set("text", source.path("text").deepCopy());
        summary.set(
                "evidenceRefs",
                source.path("evidenceRefs").deepCopy()
        );
    }

    @Override
    protected void validateVersionSpecificFields(
            JsonNode root,
            WeeklyInterpretationInput input,
            ValidationContext context
    ) {
        List<CandidateSignal> storeCandidates = WeeklyPrimarySignalPolicy.orderedStoreCandidates(input);
        JsonNode primarySignal = root.path("primarySignal");
        if (storeCandidates.isEmpty()) {
            if (!primarySignal.isNull()) {
                context.add(
                        "PRIMARY_SIGNAL_NOT_ALLOWED",
                        "$.primarySignal",
                        nullableText(primarySignal.path("candidateRef"))
                );
            }
            validateSecondaryInsights(root, null, context);
            return;
        }
        if (!primarySignal.isObject()) {
            context.add(
                    "PRIMARY_SIGNAL_REQUIRED",
                    "$.primarySignal",
                    storeCandidates.get(0).candidateRef()
            );
            validateSecondaryInsights(root, null, context);
            return;
        }

        String candidateRef = nullableText(primarySignal.path("candidateRef"));
        String expectedRef = storeCandidates.get(0).candidateRef();
        if (!expectedRef.equals(candidateRef)) {
            context.add(
                    "PRIMARY_SIGNAL_CANDIDATE_MISMATCH",
                    "$.primarySignal.candidateRef",
                    expectedRef
            );
        }
        String employeeRef = validateScope(
                primarySignal,
                "$.primarySignal",
                context
        );
        validateCategory(primarySignal, "$.primarySignal", context);
        validateCandidate(primarySignal, "$.primarySignal", context);
        validateEvidence(
                primarySignal.path("evidenceRefs"),
                "$.primarySignal.evidenceRefs",
                employeeRef,
                context
        );
        validateSecondaryInsights(root, candidateRef, context);
    }

    private void validateSecondaryInsights(
            JsonNode root,
            String primaryCandidateRef,
            ValidationContext context
    ) {
        for (int index = 0; index < root.path("insights").size(); index++) {
            JsonNode insight = root.path("insights").get(index);
            String secondaryRef = nullableText(insight.path("candidateRef"));
            if (secondaryRef == null) {
                context.add(
                        "SECONDARY_INSIGHT_CANDIDATE_REQUIRED",
                        "$.insights[" + index + "].candidateRef",
                        null
                );
            }
            if (primaryCandidateRef != null
                    && primaryCandidateRef.equals(secondaryRef)) {
                context.add(
                        "DUPLICATE_PRIMARY_CANDIDATE_REF",
                        "$.insights[" + index + "].candidateRef",
                        primaryCandidateRef
                );
            }
        }
    }
    @Override
    protected void validateVersionSpecificNarrativeEvidenceDimensions(
            JsonNode root,
            ValidationContext context
    ) {
        if (!root.path("primarySignal").isObject()) {
            return;
        }
        var primarySignals = JsonNodeFactory.instance.arrayNode();
        primarySignals.add(root.path("primarySignal"));
        validateNarrativeEvidenceDimensions(
                primarySignals,
                List.of("text"),
                "$.primarySignal",
                context
        );
    }

    @Override
    protected boolean requiresStoreHeadline() {
        return false;
    }

    private Map<String, EvidenceIndexEntry> evidence(
            WeeklyInterpretationInput input
    ) {
        Map<String, EvidenceIndexEntry> result = new HashMap<>();
        input.manifest().evidence().forEach(value ->
                result.put(value.evidenceRef(), value)
        );
        return result;
    }

    private String candidateScope(
            CandidateSignal candidate,
            Map<String, EvidenceIndexEntry> evidence
    ) {
        if (candidate.employeeRef() != null) {
            return "EMPLOYEE";
        }
        boolean teamOnly = candidate.evidenceRefs().stream()
                .map(evidence::get)
                .allMatch(value -> value != null
                        && value.scope() == WeeklyInterpretationInput.Scope.TEAM);
        return teamOnly ? "TEAM" : "STORE";
    }

    private void putNullIfMissing(ObjectNode node, String field) {
        if (!node.has(field)) {
            node.putNull(field);
        }
    }

    private void nullable(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }
}
