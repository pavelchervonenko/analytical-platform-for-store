package com.storeanalytics.interpretation.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.EvidenceIndexEntry;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Facts;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Manifest;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Period;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.QualityStatus;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Scope;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Snapshot;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Sufficiency;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Versions;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class WeeklyInterpretationV2ResponseValidatorTest {

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .findAndAddModules()
            .build();

    private WeeklyInterpretationV2ResponseValidator validator;
    private ObjectNode validContent;
    private WeeklyInterpretationInput input;

    @BeforeEach
    void setUp() throws JacksonException {
        validator = new WeeklyInterpretationV2ResponseValidator();
        validContent = (ObjectNode) objectMapper.readTree(resource(
                "contracts/llm/examples/weekly-interpretation-content-v2-ready.json"
        ));
        input = inputFor(validContent);
    }

    @Test
    void acceptsFlatContentGroundedInManifest() {
        LlmResponseValidationResult result = validator.validate(
                input,
                json(validContent)
        );

        assertThat(result.outcome()).isEqualTo(LlmValidationOutcome.VALID);
        assertThat(result.violations()).isEmpty();
        assertThat(result.canonicalContent()).isNotBlank();
    }

    @Test
    void rejectsMalformedV2ContractBeforeSemanticChecks() {
        validContent.remove("summaryBlocks");

        LlmResponseValidationResult result = validator.validate(
                input,
                json(validContent)
        );

        assertThat(result.outcome())
                .isEqualTo(LlmValidationOutcome.STRUCTURAL_INVALID);
    }

    @Test
    void rejectsEmployeeSetAndStatusMismatch() {
        ArrayNode employees = (ArrayNode) validContent.path("employees");
        ((ObjectNode) employees.get(0)).put("analysisStatus", "LIMITED");
        employees.addObject()
                .put("employeeRef", "E02")
                .put("analysisStatus", "SUFFICIENT");

        LlmResponseValidationResult result = validator.validate(
                input,
                json(validContent)
        );

        assertThat(result.outcome())
                .isEqualTo(LlmValidationOutcome.SEMANTIC_INVALID);
        assertThat(result.violations())
                .extracting(LlmValidationViolation::code)
                .contains("EMPLOYEE_SET_MISMATCH", "EMPLOYEE_STATUS_MISMATCH");
    }

    @Test
    void restoresMissingWorkloadSummaryFromBackendOwnedSufficiency() throws JacksonException {
        ArrayNode summaries = (ArrayNode) validContent.path("summaryBlocks");
        for (int index = summaries.size() - 1; index >= 0; index--) {
            JsonNode summary = summaries.get(index);
            if ("E01".equals(summary.path("employeeRef").asText())
                    && "WORKLOAD".equals(summary.path("section").asText())) {
                summaries.remove(index);
            }
        }

        LlmResponseValidationResult result = validator.validate(
                input,
                json(validContent)
        );

        assertThat(result.outcome()).isEqualTo(LlmValidationOutcome.VALID);
        JsonNode normalized = objectMapper.readTree(result.canonicalContent());
        JsonNode restored = null;
        for (JsonNode summary : normalized.path("summaryBlocks")) {
            if ("E01".equals(summary.path("employeeRef").asText())
                    && "WORKLOAD".equals(summary.path("section").asText())) {
                restored = summary;
            }
        }
        assertThat(restored).isNotNull();
        assertThat(restored.path("text").asText()).isEqualTo(
                "Рабочая нагрузка достаточна для анализа доступных направлений."
        );
        assertThat(restored.path("evidenceRefs").get(0).asText())
                .isEqualTo("EMP:E01.WORKLOAD.STATUS");
    }

    @Test
    void rejectsMissingWorkloadSummaryWithoutBackendOwnedWorkloadFact() {
        ArrayNode summaries = (ArrayNode) validContent.path("summaryBlocks");
        for (int index = summaries.size() - 1; index >= 0; index--) {
            JsonNode summary = summaries.get(index);
            if ("E01".equals(summary.path("employeeRef").asText())
                    && "WORKLOAD".equals(summary.path("section").asText())) {
                summaries.remove(index);
            }
        }
        Facts facts = input.facts();
        List<WeeklyInterpretationInput.EmployeeFacts> employees = facts.employees()
                .stream()
                .map(employee -> "E01".equals(employee.employeeRef())
                        ? new WeeklyInterpretationInput.EmployeeFacts(
                                employee.employeeRef(),
                                employee.analysisStatus(),
                                employee.availableSections(),
                                List.of()
                        )
                        : employee)
                .toList();
        input = new WeeklyInterpretationInput(
                input.contractVersion(),
                input.snapshot(),
                input.manifest(),
                new Facts(
                        facts.store(),
                        facts.team(),
                        employees,
                        facts.candidateSignals()
                )
        );

        LlmResponseValidationResult result = validator.validate(
                input,
                json(validContent)
        );

        assertThat(result.outcome())
                .isEqualTo(LlmValidationOutcome.SEMANTIC_INVALID);
        assertThat(result.violations())
                .extracting(LlmValidationViolation::code)
                .contains("EMPLOYEE_WORKLOAD_COUNT_MISMATCH");
    }

    @Test
    void rejectsCrossEmployeeEvidence() {
        String evidenceRef = "EMP:E02.WORKLOAD.STATUS";
        input = inputWithEvidence(
                input,
                new EvidenceIndexEntry(
                        evidenceRef, Scope.EMPLOYEE, "E02", true
                )
        );
        ArrayNode evidence = (ArrayNode) validContent.path("summaryBlocks")
                .get(2).path("evidenceRefs");
        evidence.set(0, objectMapper.getNodeFactory().stringNode(
                evidenceRef
        ));

        LlmResponseValidationResult result = validator.validate(
                input,
                json(validContent)
        );

        assertThat(result.outcome())
                .isEqualTo(LlmValidationOutcome.SEMANTIC_INVALID);
        assertThat(result.violations())
                .extracting(LlmValidationViolation::code)
                .contains("CROSS_EMPLOYEE_EVIDENCE");
    }
    @Test
    void rejectsAnalysisForInsufficientEmployee() {
        ((ObjectNode) validContent.path("employees").get(0))
                .put("analysisStatus", "INSUFFICIENT");
        input = inputFor(validContent);

        LlmResponseValidationResult result = validator.validate(
                input,
                json(validContent)
        );

        assertThat(result.outcome())
                .isEqualTo(LlmValidationOutcome.SEMANTIC_INVALID);
        assertThat(result.violations())
                .extracting(LlmValidationViolation::code)
                .contains(
                        "INSUFFICIENT_SECTION_PRESENT",
                        "INSUFFICIENT_INSIGHT_PRESENT"
                );
    }

    @Test
    void rejectsUnavailableSectionForLimitedEmployee() {
        ((ObjectNode) validContent.path("employees").get(0))
                .put("analysisStatus", "LIMITED");
        input = inputFor(validContent);
        WeeklyInterpretationInput.EmployeeFacts source =
                input.facts().employees().get(0);
        input = new WeeklyInterpretationInput(
                input.contractVersion(),
                input.snapshot(),
                input.manifest(),
                new Facts(
                        input.facts().store(),
                        input.facts().team(),
                        List.of(new WeeklyInterpretationInput.EmployeeFacts(
                                source.employeeRef(),
                                Sufficiency.LIMITED,
                                List.of("WORKLOAD"),
                                source.facts()
                        )),
                        input.facts().candidateSignals()
                )
        );

        LlmResponseValidationResult result = validator.validate(
                input,
                json(validContent)
        );

        assertThat(result.outcome())
                .isEqualTo(LlmValidationOutcome.SEMANTIC_INVALID);
        assertThat(result.violations())
                .extracting(LlmValidationViolation::code)
                .contains("UNAVAILABLE_EMPLOYEE_SECTION");
    }

    @Test
    void rejectsNumericAndTechnicalNarrative() {
        ObjectNode insight = (ObjectNode) validContent.path("insights").get(0);
        insight.put("title", "Код SERVICE дал рост на 12 процентов");

        LlmResponseValidationResult result = validator.validate(
                input,
                json(validContent)
        );

        assertThat(result.outcome())
                .isEqualTo(LlmValidationOutcome.SEMANTIC_INVALID);
        assertThat(result.violations())
                .extracting(LlmValidationViolation::code)
                .contains(
                        "FORBIDDEN_NARRATIVE_LITERAL",
                        "FORBIDDEN_TECHNICAL_IDENTIFIER"
                );
    }

    @Test
    void normalizesSpecificTargetsOutOfStoreAndTeamActions() throws Exception {
        ArrayNode actions = (ArrayNode) validContent.path("actions");
        ObjectNode first = (ObjectNode) actions.get(0);
        first.put("targetScope", "STORE");
        ((ArrayNode) first.path("targetEmployeeRefs")).add("E01");

        LlmResponseValidationResult result = validator.validate(
                input,
                json(validContent)
        );

        assertThat(result.outcome()).isEqualTo(LlmValidationOutcome.VALID);
        JsonNode canonical = objectMapper.readTree(result.canonicalContent());
        assertThat(canonical.at("/actions/0/targetEmployeeRefs").isEmpty())
                .isTrue();
    }

    @Test
    void rejectsDuplicateActions() {
        ArrayNode actions = (ArrayNode) validContent.path("actions");
        ObjectNode first = (ObjectNode) actions.get(0);
        ObjectNode duplicate = first.deepCopy();
        duplicate.put("title", "Другая формулировка");
        duplicate.put("summary", "Повторить то же управленческое решение.");
        actions.add(duplicate);

        LlmResponseValidationResult result = validator.validate(
                input,
                json(validContent)
        );

        assertThat(result.outcome())
                .isEqualTo(LlmValidationOutcome.SEMANTIC_INVALID);
        assertThat(result.violations())
                .extracting(LlmValidationViolation::code)
                .contains("DUPLICATE_RECOMMENDED_ACTION");
    }

    @Test
    void removesRejectedOptionalTeamRelationship() throws Exception {
        ObjectNode relationship = (ObjectNode) validContent
                .path("teamRelationships").get(0);
        relationship.put("type", "LEARNING_OPPORTUNITY");
        ((ArrayNode) relationship.path("targetEmployeeRefs")).add("E01");

        LlmResponseValidationResult result = validator.validate(
                input,
                json(validContent)
        );

        assertThat(result.outcome()).isEqualTo(LlmValidationOutcome.VALID);
        JsonNode canonical = objectMapper.readTree(result.canonicalContent());
        assertThat(canonical.path("teamRelationships").isEmpty()).isTrue();
    }

    @Test
    void removesUnsupportedOptionalRiskInsight() throws Exception {
        ObjectNode insight = (ObjectNode) validContent.path("insights").get(0);
        insight.put("kind", "RISK");
        insight.put("title", "Риск снижения выручки");
        ArrayNode evidence = (ArrayNode) insight.path("evidenceRefs");
        evidence.removeAll();
        evidence.add("STORE.MARGIN_PERCENT.DELTA");

        LlmResponseValidationResult result = validator.validate(
                input,
                json(validContent)
        );

        assertThat(result.outcome()).isEqualTo(LlmValidationOutcome.VALID);
        JsonNode canonical = objectMapper.readTree(result.canonicalContent());
        assertThat(canonical.path("insights").size()).isEqualTo(1);
    }

    @Test
    void replacesProviderLimitationsWithBackendOwnedSet() {
        ((ArrayNode) validContent.path("dataLimitations")).addObject()
                .put("code", "INVENTED");

        LlmResponseValidationResult result = validator.validate(
                input,
                json(validContent)
        );

        assertThat(result.outcome()).isEqualTo(LlmValidationOutcome.VALID);
        assertThat(result.canonicalContent()).doesNotContain("INVENTED");
    }

    @Test
    void versionRouterSelectsExactStrategyAndRejectsUnknownVersion() {
        VersionedWeeklyInterpretationResponseValidator router =
                new VersionedWeeklyInterpretationResponseValidator(List.of(
                        new WeeklyInterpretationResponseValidator(),
                        validator
                ));

        assertThat(router.validate(2, input, json(validContent)).outcome())
                .isEqualTo(LlmValidationOutcome.VALID);
        assertThatThrownBy(() -> router.validate(3, input, json(validContent)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported LLM content schema version");
    }
    @Test
    void restoresOmittedNullableProviderFieldsBeforeCanonicalValidation() {
        for (JsonNode value : validContent.path("summaryBlocks")) {
            ObjectNode summary = (ObjectNode) value;
            List.of("employeeRef", "categoryCode").stream()
                    .filter(field -> summary.path(field).isNull())
                    .forEach(summary::remove);
        }
        for (JsonNode value : validContent.path("insights")) {
            ObjectNode insight = (ObjectNode) value;
            List.of("employeeRef", "categoryCode", "candidateRef").stream()
                    .filter(field -> insight.path(field).isNull())
                    .forEach(insight::remove);
        }

        LlmResponseValidationResult result = validator.validate(
                input,
                json(validContent)
        );

        assertThat(result.outcome()).isEqualTo(LlmValidationOutcome.VALID);
        assertThat(result.canonicalContent())
                .contains(
                        "\"employeeRef\":null",
                        "\"categoryCode\":null",
                        "\"candidateRef\":null"
                );
    }

    private WeeklyInterpretationInput inputFor(JsonNode content) {
        Set<String> employeeRefs = new LinkedHashSet<>();
        List<WeeklyInterpretationInput.EmployeeFacts> employeeFacts =
                new ArrayList<>();
        for (JsonNode employee : content.path("employees")) {
            String reference = employee.path("employeeRef").asText();
            Sufficiency analysisStatus = Sufficiency.valueOf(
                    employee.path("analysisStatus").asText()
            );
            employeeRefs.add(reference);
            employeeFacts.add(new WeeklyInterpretationInput.EmployeeFacts(
                    reference,
                    analysisStatus,
                    List.of(
                            "WORKLOAD", "RESULT", "RATING",
                            "CATEGORIES", "ATTACH"
                    ),
                    List.of(new WeeklyInterpretationInput.Fact(
                            "EMP:" + reference + ".WORKLOAD.STATUS",
                            "WORKLOAD_STATUS",
                            null,
                            WeeklyInterpretationInput.Unit.STATUS,
                            analysisStatus.name(),
                            null,
                            analysisStatus,
                            WeeklyInterpretationInput.Materiality.CONTEXT
                    ))
            ));
        }
        Set<String> evidenceRefs = new LinkedHashSet<>();
        Set<String> candidateRefs = new LinkedHashSet<>();
        Set<String> categoryCodes = new LinkedHashSet<>();
        Set<String> competencyCodes = new LinkedHashSet<>();
        collectManifestValues(
                content,
                null,
                evidenceRefs,
                candidateRefs,
                categoryCodes,
                competencyCodes
        );
        List<EvidenceIndexEntry> evidence = evidenceRefs.stream()
                .map(reference -> evidence(reference, employeeRefs))
                .toList();
        Manifest manifest = new Manifest(
                List.copyOf(employeeRefs),
                evidence,
                List.copyOf(candidateRefs),
                List.copyOf(categoryCodes),
                List.copyOf(competencyCodes),
                List.of()
        );
        Snapshot snapshot = new Snapshot(
                UUID.fromString("00000000-0000-4000-8000-000000000001"),
                1,
                "a".repeat(64),
                "S01",
                "Europe/Kaliningrad",
                new Period(
                        LocalDate.of(2026, 7, 27),
                        LocalDate.of(2026, 8, 2)
                ),
                new Period(
                        LocalDate.of(2026, 7, 20),
                        LocalDate.of(2026, 7, 26)
                ),
                QualityStatus.READY,
                new Versions(
                        1,
                        "weekly-v1",
                        "calculation-v1",
                        "quality-v1"
                )
        );
        return new WeeklyInterpretationInput(
                1,
                snapshot,
                manifest,
                new Facts(
                        List.of(),
                        List.of(),
                        employeeFacts,
                        List.of()
                )
        );
    }

    private WeeklyInterpretationInput inputWithEvidence(
            WeeklyInterpretationInput source,
            EvidenceIndexEntry additionalEvidence
    ) {
        List<EvidenceIndexEntry> evidence = new ArrayList<>(
                source.manifest().evidence()
        );
        evidence.add(additionalEvidence);
        Manifest manifest = source.manifest();
        return new WeeklyInterpretationInput(
                source.contractVersion(),
                source.snapshot(),
                new Manifest(
                        manifest.employeeRefs(),
                        evidence,
                        manifest.candidateRefs(),
                        manifest.categoryCodes(),
                        manifest.competencyCodes(),
                        manifest.limitations()
                ),
                source.facts()
        );
    }

    private void collectManifestValues(
            JsonNode node,
            String fieldName,
            Set<String> evidenceRefs,
            Set<String> candidateRefs,
            Set<String> categoryCodes,
            Set<String> competencyCodes
    ) {
        if (node.isArray()) {
            node.forEach(value -> collectManifestValues(
                    value,
                    fieldName,
                    evidenceRefs,
                    candidateRefs,
                    categoryCodes,
                    competencyCodes
            ));
            return;
        }
        if (node.isObject()) {
            for (String field : node.propertyNames()) {
                collectManifestValues(
                        node.get(field),
                        field,
                        evidenceRefs,
                        candidateRefs,
                        categoryCodes,
                        competencyCodes
                );
            }
            return;
        }
        if (!node.isTextual()) {
            return;
        }
        switch (fieldName) {
            case "evidenceRefs" -> evidenceRefs.add(node.asText());
            case "candidateRef" -> candidateRefs.add(node.asText());
            case "categoryCode" -> categoryCodes.add(node.asText());
            case "competencyCode" -> competencyCodes.add(node.asText());
            default -> {
            }
        }
    }

    private EvidenceIndexEntry evidence(
            String reference,
            Set<String> employeeRefs
    ) {
        String employeeRef = employeeRefs.stream()
                .filter(value -> reference.startsWith("EMP:" + value + "."))
                .findFirst()
                .orElse(null);
        Scope scope = employeeRef == null ? Scope.STORE : Scope.EMPLOYEE;
        return new EvidenceIndexEntry(reference, scope, employeeRef, true);
    }

    private String json(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String resource(String name) {
        ClassLoader loader = getClass().getClassLoader();
        try (InputStream inputStream = loader.getResourceAsStream(name)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing resource: " + name);
            }
            return new String(
                    inputStream.readAllBytes(),
                    StandardCharsets.UTF_8
            );
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
