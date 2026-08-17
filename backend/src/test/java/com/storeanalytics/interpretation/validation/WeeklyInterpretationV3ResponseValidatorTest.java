package com.storeanalytics.interpretation.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.interpretation.contract.LlmContractResources;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput;
import com.storeanalytics.interpretation.generation.LlmProviderInputCompactor;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.CandidateKind;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.CandidateSignal;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Comparison;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.EmployeeFacts;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.EvidenceIndexEntry;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Fact;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Facts;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Manifest;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Materiality;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Period;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.QualityStatus;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Scope;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Snapshot;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Sufficiency;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Unit;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Versions;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class WeeklyInterpretationV3ResponseValidatorTest {

    private static final String STORE_EVIDENCE = "STORE.NET_REVENUE.CURRENT";
    private static final String TEAM_EVIDENCE = "TEAM.RATING.ELIGIBLE_COUNT";
    private static final String EMPLOYEE_EVIDENCE =
            "EMP:E01.NET_REVENUE.CURRENT";

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .findAndAddModules()
            .build();

    private WeeklyInterpretationV3ResponseValidator validator;
    private ObjectNode content;

    @BeforeEach
    void setUp() throws JacksonException {
        validator = new WeeklyInterpretationV3ResponseValidator();
        content = (ObjectNode) objectMapper.readTree("""
                {
                  "employees": [{
                    "employeeRef": "E01",
                    "analysisStatus": "SUFFICIENT"
                  }],
                  "primarySignal": {
                    "scope": "STORE",
                    "employeeRef": null,
                    "categoryCode": null,
                    "kind": "OPPORTUNITY",
                    "theme": "REVENUE_DYNAMICS",
                    "candidateRef": "C001",
                    "text": "Выручка магазина выросла относительно прошлой недели.",
                    "evidenceRefs": ["STORE.NET_REVENUE.CURRENT"]
                  },
                  "summaryBlocks": [{
                    "scope": "TEAM",
                    "employeeRef": null,
                    "section": "TEAM_OVERVIEW",
                    "categoryCode": null,
                    "text": "По команде доступен подтверждённый общий контекст.",
                    "evidenceRefs": ["TEAM.RATING.ELIGIBLE_COUNT"]
                  }, {
                    "scope": "EMPLOYEE",
                    "employeeRef": "E01",
                    "section": "HEADLINE",
                    "categoryCode": null,
                    "text": "Результат сотрудника сопоставим с прошлой неделей.",
                    "evidenceRefs": ["EMP:E01.NET_REVENUE.CURRENT"]
                  }],
                  "insights": [],
                  "actions": [],
                  "teamRelationships": [],
                  "dataLimitations": []
                }
                """);
    }

    @Test
    void acceptsAndNormalizesBackendOwnedPrimarySignal() throws Exception {
        ObjectNode primary = (ObjectNode) content.path("primarySignal");
        primary.put("kind", "RISK");
        primary.put("theme", "OTHER");
        primary.put("scope", "EMPLOYEE");
        primary.put("employeeRef", "E01");
        primary.putArray("evidenceRefs").add(EMPLOYEE_EVIDENCE);

        LlmResponseValidationResult result = validator.validate(
                input(List.of(storeCandidate("C001", "REVENUE_DYNAMICS"))),
                json(content)
        );

        assertThat(result.outcome()).isEqualTo(LlmValidationOutcome.VALID);
        JsonNode primarySignal = objectMapper.readTree(result.canonicalContent())
                .path("primarySignal");
        assertThat(primarySignal.path("scope").asText()).isEqualTo("STORE");
        assertThat(primarySignal.path("employeeRef").isNull()).isTrue();
        assertThat(primarySignal.path("kind").asText()).isEqualTo("OPPORTUNITY");
        assertThat(primarySignal.path("theme").asText())
                .isEqualTo("REVENUE_DYNAMICS");
        assertThat(primarySignal.path("evidenceRefs"))
                .containsExactly(objectMapper.getNodeFactory().textNode(
                        STORE_EVIDENCE
                ));
    }

    @Test
    void requiresPrimarySignalWhenStoreCandidateExists() {
        content.putNull("primarySignal");

        LlmResponseValidationResult result = validator.validate(
                input(List.of(storeCandidate("C001", "REVENUE_DYNAMICS"))),
                json(content)
        );

        assertThat(result.outcome())
                .isEqualTo(LlmValidationOutcome.SEMANTIC_INVALID);
        assertThat(result.violations())
                .extracting(LlmValidationViolation::code)
                .contains("PRIMARY_SIGNAL_REQUIRED");
    }

    @Test
    void acceptsBackendOwnedNeutralHeadlineStateWithoutStoreCandidate() {
        content.putNull("primarySignal");

        LlmResponseValidationResult result = validator.validate(
                input(List.of()),
                json(content)
        );

        assertThat(result.outcome()).isEqualTo(LlmValidationOutcome.VALID);
    }

    @Test
    void doesNotPromoteTeamOnlyCandidateToStorePrimarySignal() {
        content.putNull("primarySignal");
        CandidateSignal teamCandidate = new CandidateSignal(
                "C001",
                CandidateKind.OPPORTUNITY,
                "TEAM_PERFORMANCE",
                null,
                List.of(TEAM_EVIDENCE)
        );

        LlmResponseValidationResult result = validator.validate(
                input(List.of(teamCandidate)),
                json(content)
        );

        assertThat(result.outcome()).isEqualTo(LlmValidationOutcome.VALID);
    }
    @Test
    void rejectsPrimaryCandidateRepeatedAsSecondaryInsight() {
        addInsight("C001");

        LlmResponseValidationResult result = validator.validate(
                input(List.of(storeCandidate("C001", "REVENUE_DYNAMICS"))),
                json(content)
        );

        assertThat(result.outcome())
                .isEqualTo(LlmValidationOutcome.SEMANTIC_INVALID);
        assertThat(result.violations())
                .extracting(LlmValidationViolation::code)
                .contains("DUPLICATE_PRIMARY_CANDIDATE_REF");
    }

    @Test
    void rejectsFreelyAuthoredSecondaryInsight() {
        addInsight(null);

        LlmResponseValidationResult result = validator.validate(
                input(List.of(storeCandidate("C001", "REVENUE_DYNAMICS"))),
                json(content)
        );

        assertThat(result.outcome())
                .isEqualTo(LlmValidationOutcome.SEMANTIC_INVALID);
        assertThat(result.violations())
                .extracting(LlmValidationViolation::code)
                .contains("SECONDARY_INSIGHT_CANDIDATE_REQUIRED");
    }

    @Test
    void rejectsLegacyStoreHeadlineInPrimarySignalContract() {
        ObjectNode headline = ((ArrayNode) content.path("summaryBlocks"))
                .addObject();
        headline.put("scope", "STORE");
        headline.putNull("employeeRef");
        headline.put("section", "HEADLINE");
        headline.putNull("categoryCode");
        headline.put("text", "Выручка магазина выросла.");
        headline.putArray("evidenceRefs").add(STORE_EVIDENCE);

        LlmResponseValidationResult result = validator.validate(
                input(List.of(storeCandidate("C001", "REVENUE_DYNAMICS"))),
                json(content)
        );

        assertThat(result.outcome())
                .isEqualTo(LlmValidationOutcome.SEMANTIC_INVALID);
        assertThat(result.violations())
                .extracting(LlmValidationViolation::code)
                .contains("STORE_HEADLINE_NOT_ALLOWED");
    }

    @Test
    void requiresBackendPrioritizedPlanCandidateAsPrimary() {
        List<CandidateSignal> candidates = List.of(
                storeCandidate("C001", "REVENUE_DYNAMICS"),
                new CandidateSignal(
                        "C002",
                        CandidateKind.RISK,
                        "PLAN",
                        null,
                        List.of(STORE_EVIDENCE)
                )
        );

        LlmResponseValidationResult result = validator.validate(
                input(candidates),
                json(content)
        );

        assertThat(result.outcome())
                .isEqualTo(LlmValidationOutcome.SEMANTIC_INVALID);
        assertThat(result.violations())
                .extracting(LlmValidationViolation::code)
                .contains("PRIMARY_SIGNAL_CANDIDATE_MISMATCH");
    }

    @Test
    void explainsZeroRevenueAfterANonZeroPreviousPeriod() throws Exception {
        CandidateSignal candidate = new CandidateSignal(
                "C001",
                CandidateKind.RISK,
                "REVENUE_DYNAMICS",
                null,
                List.of(STORE_EVIDENCE)
        );
        WeeklyInterpretationInput base = input(List.of(candidate));
        Fact zeroRevenue = new Fact(
                STORE_EVIDENCE,
                "NET_REVENUE",
                null,
                Unit.MONEY,
                0,
                new Comparison(
                        new BigDecimal("850000"),
                        new BigDecimal("-850000"),
                        new BigDecimal("-100")
                ),
                Sufficiency.SUFFICIENT,
                Materiality.PRIMARY
        );
        WeeklyInterpretationInput zero = new WeeklyInterpretationInput(
                base.contractVersion(),
                base.snapshot(),
                base.manifest(),
                new Facts(
                        List.of(zeroRevenue),
                        base.facts().team(),
                        base.facts().employees(),
                        base.facts().candidateSignals()
                )
        );
        ObjectNode transport = structuredTransport();
        transport.set(
                "primarySignal",
                content.path("primarySignal").deepCopy()
        );

        LlmResponseValidationResult result = validator.validate(
                zero,
                json(transport)
        );

        assertThat(result.outcome()).isEqualTo(LlmValidationOutcome.VALID);
        JsonNode primary = objectMapper.readTree(result.canonicalContent())
                .path("primarySignal");
        assertThat(primary.path("text").asText()).isEqualTo(
                "Чистая выручка равна нулю после ненулевого значения "
                        + "прошлого периода."
        );
    }

    @Test
    void includesVerifiedMarginDirectionWithGrossProfitSignal()
            throws Exception {
        String grossEvidence = "STORE.GROSS_PROFIT.CURRENT";
        String marginEvidence = "STORE.MARGIN_PERCENT.CURRENT";
        CandidateSignal candidate = new CandidateSignal(
                "C001",
                CandidateKind.RISK,
                "PROFITABILITY",
                null,
                List.of(grossEvidence)
        );
        WeeklyInterpretationInput base = input(List.of(candidate));
        List<EvidenceIndexEntry> evidence = new ArrayList<>(
                base.manifest().evidence()
        );
        evidence.add(new EvidenceIndexEntry(
                grossEvidence, Scope.STORE, null, true
        ));
        evidence.add(new EvidenceIndexEntry(
                marginEvidence, Scope.STORE, null, true
        ));
        Manifest manifest = new Manifest(
                base.manifest().employeeRefs(),
                evidence,
                List.of("C001"),
                List.of(),
                List.of(),
                List.of()
        );
        Fact grossProfit = new Fact(
                grossEvidence,
                "GROSS_PROFIT",
                null,
                Unit.MONEY,
                168000,
                new Comparison(
                        new BigDecimal("210000"),
                        new BigDecimal("-42000"),
                        new BigDecimal("-20")
                ),
                Sufficiency.SUFFICIENT,
                Materiality.PRIMARY
        );
        Fact margin = new Fact(
                marginEvidence,
                "MARGIN_PERCENT",
                null,
                Unit.PERCENT,
                12,
                new Comparison(
                        new BigDecimal("21"),
                        new BigDecimal("-9"),
                        null
                ),
                Sufficiency.SUFFICIENT,
                Materiality.SECONDARY
        );
        WeeklyInterpretationInput profitability =
                new WeeklyInterpretationInput(
                        base.contractVersion(),
                        base.snapshot(),
                        manifest,
                        new Facts(
                                List.of(grossProfit, margin),
                                base.facts().team(),
                                base.facts().employees(),
                                List.of(candidate)
                        )
                );
        ObjectNode transport = structuredTransport();
        transport.set(
                "primarySignal",
                content.path("primarySignal").deepCopy()
        );

        LlmResponseValidationResult result = validator.validate(
                profitability,
                json(transport)
        );

        assertThat(result.outcome()).isEqualTo(LlmValidationOutcome.VALID);
        JsonNode primary = objectMapper.readTree(result.canonicalContent())
                .path("primarySignal");
        assertThat(primary.path("text").asText()).isEqualTo(
                "Валовая прибыль и маржинальность существенно снизились "
                        + "относительно прошлого периода."
        );
        assertThat(primary.path("evidenceRefs"))
                .containsExactly(
                        objectMapper.getNodeFactory().textNode(grossEvidence),
                        objectMapper.getNodeFactory().textNode(marginEvidence)
                );
    }

    @Test
    void normalizesStructuredProviderSummariesIntoCanonicalV3() throws Exception {
        ObjectNode transport = content.deepCopy();
        transport.remove("employees");
        transport.remove("summaryBlocks");
        ObjectNode teamOverview = transport.putObject("teamOverview");
        teamOverview.put(
                "text",
                "A confirmed team context is available."
        );
        teamOverview.putArray("evidenceRefs").add(TEAM_EVIDENCE);
        ObjectNode employeeHeadline = transport
                .putObject("employeeHeadlines")
                .putObject("E01");
        employeeHeadline.put(
                "text",
                "Employee result is comparable with the previous week."
        );
        employeeHeadline.putArray("evidenceRefs").add(EMPLOYEE_EVIDENCE);
        transport.putArray("supportingSummaries");

        LlmResponseValidationResult result = validator.validate(
                input(List.of(storeCandidate("C001", "REVENUE_DYNAMICS"))),
                json(transport)
        );

        assertThat(result.outcome()).isEqualTo(LlmValidationOutcome.VALID);
        JsonNode canonical = objectMapper.readTree(result.canonicalContent());
        assertThat(canonical.has("teamOverview")).isFalse();
        assertThat(canonical.has("employeeHeadlines")).isFalse();
        assertThat(canonical.has("supportingSummaries")).isFalse();
        assertThat(canonical.path("employees")).hasSize(1);
        assertThat(canonical.at("/employees/0/employeeRef").asText())
                .isEqualTo("E01");
        assertThat(canonical.at("/primarySignal/text").asText())
                .isEqualTo(
                        "Чистая выручка (продажи за вычетом "
                                + "возвратов) существенно выросла "
                                + "относительно прошлого периода."
                );
        assertThat(canonical.path("summaryBlocks"))
                .extracting(block -> block.path("scope").asText()
                        + ":" + block.path("section").asText())
                .containsExactly(
                        "TEAM:TEAM_OVERVIEW",
                        "EMPLOYEE:HEADLINE"
                );
    }

    @Test
    void materializesBackendOwnedEmployeeHeadlinesFromFullSnapshot()
            throws Exception {
        ObjectNode transport = content.deepCopy();
        transport.remove("employees");
        transport.remove("summaryBlocks");
        ObjectNode teamOverview = transport.putObject("teamOverview");
        teamOverview.put("text", "A confirmed team context is available.");
        teamOverview.putArray("evidenceRefs").add(TEAM_EVIDENCE);
        transport.putArray("supportingSummaries");
        transport.put("backendEmployeeHeadlines", true);
        CandidateSignal employeeCandidate = new CandidateSignal(
                "C002",
                CandidateKind.OPPORTUNITY,
                "EMPLOYEE_PERFORMANCE",
                "E01",
                List.of(EMPLOYEE_EVIDENCE)
        );

        LlmResponseValidationResult result = validator.validate(
                input(List.of(
                        storeCandidate("C001", "REVENUE_DYNAMICS"),
                        employeeCandidate
                )),
                json(transport)
        );

        assertThat(result.outcome()).isEqualTo(LlmValidationOutcome.VALID);
        JsonNode canonical = objectMapper.readTree(result.canonicalContent());
        assertThat(canonical.has("backendEmployeeHeadlines")).isFalse();
        JsonNode headline = canonical.path("summaryBlocks").get(1);
        assertThat(headline.path("scope").asText()).isEqualTo("EMPLOYEE");
        assertThat(headline.path("employeeRef").asText()).isEqualTo("E01");
        assertThat(headline.path("text").asText()).isEqualTo(
                "Результат сотрудника существенно улучшился относительно "
                        + "его прошлого периода."
        );
        assertThat(headline.path("evidenceRefs"))
                .containsExactly(objectMapper.getNodeFactory().textNode(
                        EMPLOYEE_EVIDENCE
                ));
    }

    @Test
    void buildsStructuredTeamRelationshipsFromBackendCandidates()
            throws Exception {
        ObjectNode transport = content.deepCopy();
        transport.remove("employees");
        transport.remove("summaryBlocks");
        ObjectNode teamOverview = transport.putObject("teamOverview");
        teamOverview.put("text", "A confirmed team context is available.");
        teamOverview.putArray("evidenceRefs").add(TEAM_EVIDENCE);
        ObjectNode employeeHeadline = transport
                .putObject("employeeHeadlines")
                .putObject("E01");
        employeeHeadline.put(
                "text",
                "Employee result is comparable with the previous week."
        );
        employeeHeadline.putArray("evidenceRefs").add(EMPLOYEE_EVIDENCE);
        transport.putArray("supportingSummaries");
        transport.putArray("teamRelationships");

        CandidateSignal relationship = new CandidateSignal(
                "C002",
                CandidateKind.OPPORTUNITY,
                "MOST_IMPROVED",
                "E01",
                null,
                null,
                List.of(),
                Sufficiency.SUFFICIENT,
                List.of(EMPLOYEE_EVIDENCE)
        );
        LlmResponseValidationResult result = validator.validate(
                input(List.of(
                        storeCandidate("C001", "REVENUE_DYNAMICS"),
                        relationship
                )),
                json(transport)
        );

        assertThat(result.outcome()).isEqualTo(LlmValidationOutcome.VALID);
        JsonNode canonical = objectMapper.readTree(result.canonicalContent());
        JsonNode normalized = canonical.at("/teamRelationships/0");
        assertThat(canonical.path("teamRelationships")).hasSize(1);
        assertThat(normalized.path("type").asText())
                .isEqualTo("MOST_IMPROVED");
        assertThat(normalized.path("sourceEmployeeRefs"))
                .containsExactly(objectMapper.getNodeFactory().textNode("E01"));
        assertThat(normalized.path("targetEmployeeRefs")).isEmpty();
        assertThat(normalized.path("evidenceRefs"))
                .containsExactly(objectMapper.getNodeFactory().textNode(
                        EMPLOYEE_EVIDENCE
                ));
    }

    @Test
    void addsBackendOwnedStoreResultWhenNoStoreCandidateExists()
            throws Exception {
        LlmResponseValidationResult result = validator.validate(
                input(List.of()),
                json(structuredTransport())
        );

        assertThat(result.outcome()).isEqualTo(LlmValidationOutcome.VALID);
        JsonNode canonical = objectMapper.readTree(result.canonicalContent());
        JsonNode storeResult = canonical.path("summaryBlocks").get(0);
        assertThat(storeResult.path("scope").asText()).isEqualTo("STORE");
        assertThat(storeResult.path("section").asText()).isEqualTo("RESULT");
        assertThat(storeResult.path("text").asText()).isEqualTo(
                "По магазину нет отдельного существенного изменения за период."
        );
        assertThat(storeResult.path("evidenceRefs"))
                .containsExactly(objectMapper.getNodeFactory().textNode(
                        STORE_EVIDENCE
                ));
    }

    @Test
    void describesLimitedEmployeeWithoutInventingWeeklyDynamics()
            throws Exception {
        WeeklyInterpretationInput base = input(List.of());
        EmployeeFacts source = base.facts().employees().get(0);
        WeeklyInterpretationInput limited = new WeeklyInterpretationInput(
                base.contractVersion(),
                base.snapshot(),
                base.manifest(),
                new Facts(
                        base.facts().store(),
                        base.facts().team(),
                        List.of(new EmployeeFacts(
                                source.employeeRef(),
                                Sufficiency.LIMITED,
                                List.of("RESULT"),
                                source.facts()
                        )),
                        List.of()
                )
        );

        LlmResponseValidationResult result = validator.validate(
                limited,
                json(structuredTransport())
        );

        assertThat(result.outcome()).isEqualTo(LlmValidationOutcome.VALID);
        JsonNode canonical = objectMapper.readTree(result.canonicalContent());
        assertThat(canonical.path("summaryBlocks").get(2).path("text").asText())
                .isEqualTo(
                        "По сотруднику доступен только ограниченный "
                                + "текущий результат."
                );
    }

    @Test
    void reportsExactTieFromBackendOwnedEmployeeRatings() throws Exception {
        String employeeTwoEvidence =
                "EMP:E02.RATING.STRUCTURE_SCORE.CURRENT";
        WeeklyInterpretationInput base = input(List.of());
        Fact ratingOne = fact(
                EMPLOYEE_EVIDENCE,
                "RATING_STRUCTURE_SCORE",
                Unit.SCORE
        );
        Fact ratingTwo = fact(
                employeeTwoEvidence,
                "RATING_STRUCTURE_SCORE",
                Unit.SCORE
        );
        Fact eligible = new Fact(
                TEAM_EVIDENCE,
                "RATING_ELIGIBLE_COUNT",
                null,
                Unit.COUNT,
                2,
                null,
                Sufficiency.SUFFICIENT,
                Materiality.CONTEXT
        );
        List<EvidenceIndexEntry> evidence = new ArrayList<>(
                base.manifest().evidence()
        );
        evidence.add(new EvidenceIndexEntry(
                employeeTwoEvidence, Scope.EMPLOYEE, "E02", true
        ));
        Manifest manifest = new Manifest(
                List.of("E01", "E02"),
                evidence,
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        WeeklyInterpretationInput tied = new WeeklyInterpretationInput(
                base.contractVersion(),
                base.snapshot(),
                manifest,
                new Facts(
                        base.facts().store(),
                        List.of(eligible),
                        List.of(
                                new EmployeeFacts(
                                        "E01",
                                        Sufficiency.SUFFICIENT,
                                        List.of("RATING"),
                                        List.of(ratingOne)
                                ),
                                new EmployeeFacts(
                                        "E02",
                                        Sufficiency.SUFFICIENT,
                                        List.of("RATING"),
                                        List.of(ratingTwo)
                                )
                        ),
                        List.of()
                )
        );

        LlmResponseValidationResult result = validator.validate(
                tied,
                json(structuredTransport())
        );

        assertThat(result.outcome()).isEqualTo(LlmValidationOutcome.VALID);
        JsonNode overview = objectMapper.readTree(result.canonicalContent())
                .path("summaryBlocks").get(1);
        assertThat(overview.path("text").asText()).isEqualTo(
                "Результаты сотрудников по доступной компетенции равны."
        );
        assertThat(overview.path("evidenceRefs"))
                .containsExactly(
                        objectMapper.getNodeFactory().textNode(
                                EMPLOYEE_EVIDENCE
                        ),
                        objectMapper.getNodeFactory().textNode(
                                employeeTwoEvidence
                        )
                );
    }

    @Test
    void privacyReducedProductionDispatchUsesFullSnapshotForEmployees()
            throws Exception {
        WeeklyInterpretationInput full = input(List.of());
        WeeklyInterpretationInput provider =
                new LlmProviderInputCompactor().compact(full, true);
        VersionedWeeklyInterpretationResponseValidator versioned =
                new VersionedWeeklyInterpretationResponseValidator(
                        List.of(validator)
                );

        LlmResponseValidationResult result = versioned.validate(
                LlmContractResources.PRIMARY_SIGNAL_CONTENT_SCHEMA_VERSION,
                LlmContractResources.PRIVACY_REDUCED_PROMPT_VERSION,
                provider,
                full,
                json(structuredTransport())
        );

        assertThat(result.outcome())
                .withFailMessage(() -> result.violations().toString())
                .isEqualTo(LlmValidationOutcome.VALID);
        JsonNode canonical = objectMapper.readTree(result.canonicalContent());
        assertThat(canonical.path("employees")).hasSize(1);
        assertThat(canonical.path("employees").get(0)
                .path("employeeRef").asText()).isEqualTo("E01");
        assertThat(canonical.path("summaryBlocks")).anySatisfy(summary -> {
            assertThat(summary.path("scope").asText()).isEqualTo("EMPLOYEE");
            assertThat(summary.path("employeeRef").asText()).isEqualTo("E01");
        });
        assertThat(provider.manifest().employeeRefs()).isEmpty();
        assertThat(provider.facts().employees()).isEmpty();
    }

    @Test
    void privacyReducedValidationRejectsCandidateNotSentToProvider() {
        WeeklyInterpretationInput provider =
                new LlmProviderInputCompactor().compact(
                        input(List.of()),
                        true
                );
        WeeklyInterpretationInput full = input(List.of(
                storeCandidate("C001", "REVENUE_DYNAMICS")
        ));
        ObjectNode transport = structuredTransport();
        transport.set(
                "primarySignal",
                content.path("primarySignal").deepCopy()
        );

        LlmResponseValidationResult result = validator.validatePrivacyReduced(
                provider,
                full,
                json(transport)
        );

        assertThat(result.outcome())
                .isEqualTo(LlmValidationOutcome.SEMANTIC_INVALID);
        assertThat(result.violations())
                .extracting(LlmValidationViolation::code)
                .contains("PROVIDER_CANDIDATE_NOT_SENT");
    }

    @Test
    void rejectsStructuredProviderTransportWithoutEmployeeHeadline() {
        ObjectNode transport = content.deepCopy();
        transport.remove("employees");
        transport.remove("summaryBlocks");
        ObjectNode teamOverview = transport.putObject("teamOverview");
        teamOverview.put(
                "text",
                "A confirmed team context is available."
        );
        teamOverview.putArray("evidenceRefs").add(TEAM_EVIDENCE);
        transport.putObject("employeeHeadlines");
        transport.putArray("supportingSummaries");

        LlmResponseValidationResult result = validator.validate(
                input(List.of(storeCandidate("C001", "REVENUE_DYNAMICS"))),
                json(transport)
        );

        assertThat(result.outcome())
                .isEqualTo(LlmValidationOutcome.SEMANTIC_INVALID);
        assertThat(result.violations())
                .extracting(LlmValidationViolation::code)
                .contains("EMPLOYEE_HEADLINE_COUNT_MISMATCH");
    }

    private ObjectNode structuredTransport() {
        ObjectNode transport = content.deepCopy();
        transport.remove("employees");
        transport.remove("summaryBlocks");
        transport.remove("dataLimitations");
        transport.putNull("primarySignal");
        ObjectNode teamOverview = transport.putObject("teamOverview");
        teamOverview.put("text", "Provider team transport.");
        teamOverview.putArray("evidenceRefs").add(TEAM_EVIDENCE);
        transport.putArray("supportingSummaries");
        transport.put("backendEmployeeHeadlines", true);
        return transport;
    }

    private void addInsight(String candidateRef) {
        ObjectNode insight = ((ArrayNode) content.path("insights")).addObject();
        insight.put("scope", "STORE");
        insight.putNull("employeeRef");
        insight.putNull("categoryCode");
        insight.put("kind", "OPPORTUNITY");
        insight.put("theme", "REVENUE_DYNAMICS");
        if (candidateRef == null) {
            insight.putNull("candidateRef");
        } else {
            insight.put("candidateRef", candidateRef);
        }
        insight.put("title", "Рост выручки");
        insight.put("summary", "Выручка магазина выросла к прошлой неделе.");
        insight.putArray("evidenceRefs").add(STORE_EVIDENCE);
    }

    private CandidateSignal storeCandidate(String reference, String theme) {
        return new CandidateSignal(
                reference,
                CandidateKind.OPPORTUNITY,
                theme,
                null,
                List.of(STORE_EVIDENCE)
        );
    }

    private WeeklyInterpretationInput input(List<CandidateSignal> candidates) {
        Fact storeFact = fact(STORE_EVIDENCE, "NET_REVENUE", Unit.MONEY);
        Fact teamFact = fact(TEAM_EVIDENCE, "RATING_ELIGIBLE_COUNT", Unit.COUNT);
        Fact employeeFact = fact(
                EMPLOYEE_EVIDENCE,
                "NET_REVENUE",
                Unit.MONEY
        );
        List<EvidenceIndexEntry> evidence = new ArrayList<>();
        evidence.add(new EvidenceIndexEntry(
                STORE_EVIDENCE, Scope.STORE, null, true
        ));
        evidence.add(new EvidenceIndexEntry(
                TEAM_EVIDENCE, Scope.TEAM, null, true
        ));
        evidence.add(new EvidenceIndexEntry(
                EMPLOYEE_EVIDENCE, Scope.EMPLOYEE, "E01", true
        ));
        Manifest manifest = new Manifest(
                List.of("E01"),
                evidence,
                candidates.stream().map(CandidateSignal::candidateRef).toList(),
                List.of(),
                List.of(),
                List.of()
        );
        Facts facts = new Facts(
                List.of(storeFact),
                List.of(teamFact),
                List.of(new EmployeeFacts(
                        "E01",
                        Sufficiency.SUFFICIENT,
                        List.of("RESULT"),
                        List.of(employeeFact)
                )),
                candidates
        );
        return new WeeklyInterpretationInput(
                1,
                new Snapshot(
                        UUID.fromString(
                                "00000000-0000-4000-8000-000000000001"
                        ),
                        1,
                        "a".repeat(64),
                        "S01",
                        "Europe/Moscow",
                        new Period(
                                LocalDate.of(2026, 8, 3),
                                LocalDate.of(2026, 8, 9)
                        ),
                        new Period(
                                LocalDate.of(2026, 7, 27),
                                LocalDate.of(2026, 8, 2)
                        ),
                        QualityStatus.READY,
                        new Versions(
                                1,
                                "weekly-v1",
                                "calculation-v1",
                                "quality-v1"
                        )
                ),
                manifest,
                facts
        );
    }

    private Fact fact(String evidenceRef, String metricCode, Unit unit) {
        return new Fact(
                evidenceRef,
                metricCode,
                null,
                unit,
                1,
                null,
                Sufficiency.SUFFICIENT,
                Materiality.PRIMARY
        );
    }

    private String json(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
