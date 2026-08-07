package com.storeanalytics.interpretation.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.EvidenceIndexEntry;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Limitation;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.LimitationImpact;
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

class WeeklyInterpretationResponseValidatorTest {

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .findAndAddModules()
            .build();

    private WeeklyInterpretationResponseValidator validator;
    private ObjectNode validContent;
    private WeeklyInterpretationInput input;

    @BeforeEach
    void setUp() throws JacksonException {
        validator = new WeeklyInterpretationResponseValidator();
        validContent = (ObjectNode) objectMapper.readTree(resource(
                "contracts/llm/examples/weekly-interpretation-content-v1-ready.json"
        ));
        input = inputFor(validContent);
    }

    @Test
    void acceptsStructurallyValidContentGroundedInManifest() {
        LlmResponseValidationResult result = validator.validate(
                input,
                json(validContent)
        );

        assertThat(result.outcome()).isEqualTo(LlmValidationOutcome.VALID);
        assertThat(result.violations()).isEmpty();
        assertThat(result.canonicalContent()).isNotBlank();
    }

    @Test
    void rejectsMalformedContractBeforeSemanticChecks() {
        validContent.remove("store");

        LlmResponseValidationResult result = validator.validate(
                input,
                json(validContent)
        );

        assertThat(result.outcome())
                .isEqualTo(LlmValidationOutcome.STRUCTURAL_INVALID);
        assertThat(result.violations()).isNotEmpty();
    }

    @Test
    void rejectsUnknownEvidenceWithoutJudgingTheConclusion() {
        ArrayNode evidence = (ArrayNode) validContent.path("store")
                .path("headline").path("evidenceRefs");
        evidence.set(0, objectMapper.getNodeFactory().stringNode("UNKNOWN.EVIDENCE"));

        LlmResponseValidationResult result = validator.validate(
                input,
                json(validContent)
        );

        assertThat(result.outcome()).isEqualTo(LlmValidationOutcome.SEMANTIC_INVALID);
        assertThat(result.violations())
                .extracting(LlmValidationViolation::code)
                .contains("UNAVAILABLE_EVIDENCE_REF");
    }

    @Test
    void rejectsMissingEmployeeAndBackendStatusMismatch() {
        ArrayNode employees = (ArrayNode) validContent.path("employees");
        employees.remove(1);
        ((ObjectNode) employees.get(0)).put("analysisStatus", "LIMITED");

        LlmResponseValidationResult result = validator.validate(
                input,
                json(validContent)
        );

        assertThat(result.outcome()).isEqualTo(LlmValidationOutcome.SEMANTIC_INVALID);
        assertThat(result.violations())
                .extracting(LlmValidationViolation::code)
                .contains("EMPLOYEE_SET_MISMATCH", "EMPLOYEE_STATUS_MISMATCH");
    }

    @Test
    void rejectsNumericClaimInsteadOfPublishingGenericReplacement() {
        ((ObjectNode) validContent.path("store").path("headline"))
                .put("text", "Оборот вырос на 12 процентов.");

        LlmResponseValidationResult result = validator.validate(
                input,
                json(validContent)
        );

        assertThat(result.outcome())
                .isEqualTo(LlmValidationOutcome.SEMANTIC_INVALID);
        assertThat(result.violations())
                .extracting(LlmValidationViolation::code)
                .contains("FORBIDDEN_NARRATIVE_LITERAL");
    }

    @Test
    void rejectsEvidenceFromAnotherEmployeeInsideEmployeeCard() {
        ArrayNode evidence = (ArrayNode) validContent.path("employees").get(0)
                .path("headline").path("evidenceRefs");
        evidence.set(0, objectMapper.getNodeFactory().stringNode(
                "EMP:E02.WORKLOAD.STATUS"
        ));

        LlmResponseValidationResult result = validator.validate(
                input,
                json(validContent)
        );

        assertThat(result.outcome()).isEqualTo(LlmValidationOutcome.SEMANTIC_INVALID);
        assertThat(result.violations())
                .extracting(LlmValidationViolation::code)
                .contains("CROSS_EMPLOYEE_EVIDENCE");
    }

    @Test
    void replacesModelLimitationsWithBackendOwnedSet() throws JacksonException {
        JsonNode limitation = objectMapper.readTree("""
                {
                  "code": "INVENTED_LIMITATION",
                  "scope": "STORE",
                  "employeeRef": null,
                  "categoryCode": null,
                  "impact": "REDUCED_CONFIDENCE",
                  "affectedSections": ["RESULT"],
                  "summary": "Доступные данные требуют дополнительной проверки.",
                  "evidenceRefs": ["STORE.NET_REVENUE.CURRENT"]
                }
                """);
        ((ArrayNode) validContent.path("dataLimitations")).add(limitation);

        LlmResponseValidationResult result = validator.validate(
                input,
                json(validContent)
        );

        assertThat(result.outcome()).isEqualTo(LlmValidationOutcome.VALID);
        assertThat(result.canonicalContent()).doesNotContain("INVENTED_LIMITATION");
    }

    @Test
    void rejectsTargetsThatContradictActionScope() {
        JsonNode action = validContent.path("store")
                .path("recommendedActions").get(0);
        ((ArrayNode) action.path("targetEmployeeRefs"))
                .add("E01");

        LlmResponseValidationResult result = validator.validate(
                input,
                json(validContent)
        );

        assertThat(result.outcome()).isEqualTo(LlmValidationOutcome.SEMANTIC_INVALID);
        assertThat(result.violations())
                .extracting(LlmValidationViolation::code)
                .contains("ACTION_TARGET_SCOPE_MISMATCH");
    }

    @Test
    void rejectsUnavailableSectionsForLimitedEmployee() {
        JsonNode firstEmployee = validContent.path("employees").get(0);
        ((ObjectNode) firstEmployee).put("analysisStatus", "LIMITED");
        List<WeeklyInterpretationInput.EmployeeFacts> facts = input.facts()
                .employees().stream()
                .map(value -> "E01".equals(value.employeeRef())
                        ? new WeeklyInterpretationInput.EmployeeFacts(
                                value.employeeRef(),
                                Sufficiency.LIMITED,
                                List.of("WORKLOAD"),
                                value.facts()
                        )
                        : value)
                .toList();
        input = new WeeklyInterpretationInput(
                input.contractVersion(),
                input.snapshot(),
                input.manifest(),
                new Facts(
                        input.facts().store(),
                        input.facts().team(),
                        facts,
                        input.facts().candidateSignals()
                )
        );

        LlmResponseValidationResult result = validator.validate(
                input,
                json(validContent)
        );

        assertThat(result.outcome()).isEqualTo(LlmValidationOutcome.SEMANTIC_INVALID);
        assertThat(result.violations())
                .extracting(LlmValidationViolation::code)
                .contains("UNAVAILABLE_EMPLOYEE_SECTION");
    }

    @Test
    void rejectsPersonalNarrativeGroundedOnlyInStoreEvidence() {
        ArrayNode evidence = (ArrayNode) validContent.path("employees").get(0)
                .path("headline").path("evidenceRefs");
        evidence.removeAll();
        evidence.add("STORE.NET_REVENUE.CURRENT");

        LlmResponseValidationResult result = validator.validate(
                input,
                json(validContent)
        );

        assertThat(result.outcome()).isEqualTo(LlmValidationOutcome.SEMANTIC_INVALID);
        assertThat(result.violations())
                .extracting(LlmValidationViolation::code)
                .contains("EMPLOYEE_EVIDENCE_REQUIRED");
    }

    @Test
    void acceptsUnavailableEvidenceInsideExactBackendLimitation() throws JacksonException {
        String evidenceRef = "STORE.DATA_QUALITY.STATUS";
        List<EvidenceIndexEntry> evidence = new ArrayList<>(input.manifest().evidence());
        evidence.add(new EvidenceIndexEntry(evidenceRef, Scope.STORE, null, false));
        Limitation limitation = new Limitation(
                "DATA_QUALITY_LIMITED", Scope.STORE, null, null,
                LimitationImpact.UNAVAILABLE, List.of("RESULT"),
                List.of(evidenceRef)
        );
        Manifest source = input.manifest();
        input = new WeeklyInterpretationInput(
                input.contractVersion(), input.snapshot(),
                new Manifest(
                        source.employeeRefs(), evidence, source.candidateRefs(),
                        source.categoryCodes(), source.competencyCodes(),
                        List.of(limitation)
                ),
                input.facts()
        );
        JsonNode outputLimitation = objectMapper.readTree("""
                {
                  "code": "DATA_QUALITY_LIMITED",
                  "scope": "STORE",
                  "employeeRef": null,
                  "categoryCode": null,
                  "impact": "UNAVAILABLE",
                  "affectedSections": ["RESULT"],
                  "summary": "Качество исходных данных ограничивает вывод.",
                  "evidenceRefs": ["STORE.DATA_QUALITY.STATUS"]
                }
                """);
        ((ArrayNode) validContent.path("dataLimitations")).add(outputLimitation);

        LlmResponseValidationResult result = validator.validate(
                input, json(validContent)
        );

        assertThat(result.outcome()).isEqualTo(LlmValidationOutcome.VALID);
        assertThat(result.violations()).isEmpty();
    }

    @Test
    void allowsEmployeeAliasDigitsInNarrative() {
        ObjectNode headline = (ObjectNode) validContent.path("employees")
                .get(0).path("headline");
        headline.put("text", "По сотруднику E01 доступен подтверждённый вывод.");

        LlmResponseValidationResult result = validator.validate(
                input, json(validContent)
        );

        assertThat(result.outcome()).isEqualTo(LlmValidationOutcome.VALID);
    }

    @Test
    void rejectsDuplicateManagementActionDespiteDifferentWording() {
        ArrayNode actions = (ArrayNode) validContent.path("store")
                .path("recommendedActions");
        ObjectNode duplicate = (ObjectNode) actions.get(0).deepCopy();
        duplicate.put("title", "Иная формулировка того же действия");
        duplicate.put("summary", "Повторить то же управленческое решение.");
        actions.add(duplicate);

        LlmResponseValidationResult result = validator.validate(
                input, json(validContent)
        );

        assertThat(result.outcome())
                .isEqualTo(LlmValidationOutcome.SEMANTIC_INVALID);
        assertThat(result.violations())
                .extracting(LlmValidationViolation::code)
                .contains("DUPLICATE_RECOMMENDED_ACTION");
    }

    @Test
    void rejectsRevenueRiskGroundedOnlyInAttachRateEvidence() {
        ObjectNode risk = (ObjectNode) validContent.path("store")
                .path("primaryRisk");
        risk.put("title", "Риск потери выручки");
        risk.put(
                "summary",
                "Причиной может стать слабое прикрепление дополнительной услуги."
        );
        ArrayNode evidence = (ArrayNode) risk.path("evidenceRefs");
        evidence.removeAll();
        evidence.add("STORE.ATTACH:PREMIUM_PROTECTION.RATE.CURRENT");
        input = inputWithEvidence(
                input,
                new EvidenceIndexEntry(
                        "STORE.ATTACH:PREMIUM_PROTECTION.RATE.CURRENT",
                        Scope.STORE,
                        null,
                        true
                )
        );

        LlmResponseValidationResult result = validator.validate(
                input, json(validContent)
        );

        assertThat(result.outcome())
                .isEqualTo(LlmValidationOutcome.SEMANTIC_INVALID);
        assertThat(result.violations())
                .extracting(LlmValidationViolation::code)
                .contains("UNSUPPORTED_RISK_DIMENSION");
    }

    private WeeklyInterpretationInput inputFor(JsonNode content) {
        Set<String> employeeRefs = new LinkedHashSet<>();
        List<WeeklyInterpretationInput.EmployeeFacts> employeeFacts = new ArrayList<>();
        for (JsonNode employee : content.path("employees")) {
            String reference = employee.path("employeeRef").asText();
            employeeRefs.add(reference);
            employeeFacts.add(new WeeklyInterpretationInput.EmployeeFacts(
                    reference,
                    Sufficiency.valueOf(employee.path("analysisStatus").asText()),
                    List.of("RESULT", "DYNAMICS", "CATEGORY_PERFORMANCE"),
                    List.of()
            ));
        }
        Set<String> evidenceRefs = new LinkedHashSet<>();
        Set<String> candidateRefs = new LinkedHashSet<>();
        Set<String> categoryCodes = new LinkedHashSet<>();
        Set<String> competencyCodes = new LinkedHashSet<>();
        collectManifestValues(
                content, null, evidenceRefs, candidateRefs,
                categoryCodes, competencyCodes
        );
        List<EvidenceIndexEntry> evidence = evidenceRefs.stream()
                .map(reference -> evidence(reference, employeeRefs))
                .toList();
        Manifest manifest = new Manifest(
                List.copyOf(employeeRefs), evidence, List.copyOf(candidateRefs),
                List.copyOf(categoryCodes), List.copyOf(competencyCodes), List.of()
        );
        Snapshot snapshot = new Snapshot(
                UUID.fromString("00000000-0000-4000-8000-000000000001"),
                1,
                "a".repeat(64),
                "S01",
                "Europe/Kaliningrad",
                new Period(LocalDate.of(2026, 7, 27), LocalDate.of(2026, 8, 2)),
                new Period(LocalDate.of(2026, 7, 20), LocalDate.of(2026, 7, 26)),
                QualityStatus.READY,
                new Versions(1, "weekly-v1", "calculation-v1", "quality-v1")
        );
        return new WeeklyInterpretationInput(
                1,
                snapshot,
                manifest,
                new Facts(List.of(), List.of(), employeeFacts, List.of())
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
                        manifest.employeeRefs(), evidence, manifest.candidateRefs(),
                        manifest.categoryCodes(), manifest.competencyCodes(),
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
                    value, fieldName, evidenceRefs, candidateRefs,
                    categoryCodes, competencyCodes
            ));
            return;
        }
        if (node.isObject()) {
            for (String field : node.propertyNames()) {
                collectManifestValues(
                        node.get(field), field, evidenceRefs, candidateRefs,
                        categoryCodes, competencyCodes
                );
            }
            return;
        }
        if (!node.isTextual()) {
            return;
        }
        if ("evidenceRefs".equals(fieldName)) {
            evidenceRefs.add(node.asText());
        } else if ("candidateRef".equals(fieldName)) {
            candidateRefs.add(node.asText());
        } else if ("categoryCode".equals(fieldName)) {
            categoryCodes.add(node.asText());
        } else if ("competencyCode".equals(fieldName)) {
            competencyCodes.add(node.asText());
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
                throw new IllegalStateException("Missing test resource: " + name);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
