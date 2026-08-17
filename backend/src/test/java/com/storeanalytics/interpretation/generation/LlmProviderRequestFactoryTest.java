package com.storeanalytics.interpretation.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput;
import com.storeanalytics.interpretation.snapshot.PersistedWeeklySnapshot;
import com.storeanalytics.interpretation.snapshot.WeeklyAnalyticsFactsQuery;
import com.storeanalytics.interpretation.snapshot.WeeklySnapshotPayload;
import com.storeanalytics.interpretation.snapshot.WeeklySnapshotStore;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class LlmProviderRequestFactoryTest {

    private static final Instant NOW = Instant.parse("2026-08-03T05:00:00Z");
    private static final UUID SNAPSHOT_ID = UUID.fromString(
            "00000000-0000-4000-8000-000000000001"
    );

    @Test
    void buildsSchemaValidPseudonymizedDeterministicRequest() throws IOException {
        WeeklySnapshotStore snapshotStore = mock(WeeklySnapshotStore.class);
        when(snapshotStore.findById(SNAPSHOT_ID)).thenReturn(Optional.of(snapshot()));
        LlmValidationRetryPromptFactory retryPromptFactory = mock(
                LlmValidationRetryPromptFactory.class
        );
        when(retryPromptFactory.appendRetryInstruction(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        LlmProviderRequestFactory factory = new LlmProviderRequestFactory(
                snapshotStore,
                retryPromptFactory,
                new LlmProviderInputCompactor()
        );
        LlmAnalysisJob job = job();

        PreparedLlmProviderRequest first = factory.prepare(
                job, NOW, Duration.ofSeconds(90)
        );
        PreparedLlmProviderRequest second = factory.prepare(
                job, NOW, Duration.ofSeconds(90)
        );

        assertThat(first).isEqualTo(second);
        assertThat(first.requestHash()).matches("[a-f0-9]{64}");
        assertThat(first.request().callDeadline()).isEqualTo(NOW.plusSeconds(90));
        assertThat(first.request().systemPrompt())
                .contains("Produce a concise weekly interpretation in Russian")
                .doesNotContain("api-key");
        JsonNode input = new ObjectMapper().readTree(first.request().inputJson());
        assertThat(input.at("/manifest/evidence"))
                .extracting(JsonNode::toString)
                .asString()
                .contains(
                        "STORE.NET_REVENUE.CURRENT",
                        "EMP:E01.WORKLOAD.STATUS"
                );
        assertThat(input.at("/snapshot/storeRef").textValue()).isEqualTo("S01");
        assertThat(input.at("/snapshot/snapshotRef").textValue())
                .isEqualTo(SNAPSHOT_ID.toString());
        assertThat(first.request().responseSchemaJson())
                .doesNotContain(
                        "WeeklyInterpretationContent v1",
                        "\"pattern\""
                );
        JsonNode responseSchema = new ObjectMapper().readTree(
                first.request().responseSchemaJson()
        );
        int employeeCount = input.at("/manifest/employeeRefs").size();
        assertThat(responseSchema.at("/properties/employees/minItems").asInt())
                .isEqualTo(employeeCount);
        assertThat(responseSchema.at("/properties/employees/maxItems").asInt())
                .isEqualTo(employeeCount);
        assertThat(responseSchema.at(
                "/properties/employees/items/properties/employeeRef/type"
        ).asText()).isEqualTo("string");
        assertThat(responseSchema.at(
                "/properties/store/properties/primaryRisk/type"
        ).asText()).isEqualTo("object");
        assertThat(responseSchema.at(
                "/properties/store/properties/primaryRisk/description"
        ).asText()).contains(
                "kind:string",
                "title:string(maxLength=120)",
                "evidenceRefs:[string(maxLength=160)](maxItems=8)"
        );
        assertThat(responseSchema.at(
                "/properties/store/properties/primaryRisk/properties"
        ).isMissingNode()).isTrue();
        assertThat(responseSchema.at(
                "/properties/store/additionalProperties"
        ).asBoolean()).isFalse();
        assertThat(responseSchema.at(
                "/properties/store/properties/recommendedActions/maxItems"
        ).asInt()).isEqualTo(1);
        assertThat(first.request().responseSchemaJson())
                .doesNotContain(
                        "\"$ref\"",
                        "\"$defs\"",
                        "\"anyOf\""
                );
    }

    @Test
    void buildsFullBoundedFlatV2ProviderSchema() throws IOException {
        WeeklySnapshotStore snapshotStore = mock(WeeklySnapshotStore.class);
        when(snapshotStore.findById(SNAPSHOT_ID)).thenReturn(Optional.of(snapshot()));
        LlmValidationRetryPromptFactory retryPromptFactory = mock(
                LlmValidationRetryPromptFactory.class
        );
        when(retryPromptFactory.appendRetryInstruction(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        LlmProviderRequestFactory factory = new LlmProviderRequestFactory(
                snapshotStore,
                retryPromptFactory,
                new LlmProviderInputCompactor()
        );

        PreparedLlmProviderRequest prepared = factory.prepare(
                job("weekly-interpretation-v4", 2),
                NOW,
                Duration.ofSeconds(90)
        );

        assertThat(prepared.request().systemPrompt())
                .contains(
                        "WeeklyInterpretationContent v2",
                        "The contract is intentionally flat"
                );
        JsonNode responseSchema = new ObjectMapper().readTree(
                prepared.request().responseSchemaJson()
        );
        assertThat(responseSchema.path("properties").has("dataLimitations"))
                .isFalse();
        assertThat(responseSchema.path("required").toString())
                .doesNotContain("dataLimitations");
        assertThat(responseSchema.at(
                "/properties/summaryBlocks/items/properties/text/type"
        ).asText()).isEqualTo("string");
        assertThat(responseSchema.at(
                "/properties/insights/items/properties/title/type"
        ).asText()).isEqualTo("string");
        assertThat(responseSchema.at(
                "/properties/actions/items/properties/targetScope/type"
        ).asText()).isEqualTo("string");
        assertThat(responseSchema.at(
                "/properties/teamRelationships/items/properties/type/type"
        ).asText()).isEqualTo("string");
        assertThat(responseSchema.at(
                "/properties/summaryBlocks/items/required"
        ).toString()).contains("employeeRef", "categoryCode");
        assertThat(responseSchema.at(
                "/properties/insights/items/required"
        ).toString()).contains(
                "employeeRef",
                "categoryCode",
                "candidateRef"
        );
        assertThat(responseSchema.at(
                "/properties/summaryBlocks/items/properties/employeeRef/anyOf/0/enum/0"
        ).asText()).isEqualTo("E01");
        assertThat(responseSchema.at(
                "/properties/summaryBlocks/items/properties/categoryCode"
        ).path("type").asText()).isEqualTo("null");
        assertThat(responseSchema.at(
                "/properties/summaryBlocks/minItems"
        ).asInt()).isEqualTo(4);
        assertThat(responseSchema.at(
                "/properties/summaryBlocks/maxItems"
        ).asInt()).isEqualTo(8);
        assertThat(responseSchema.at(
                "/properties/insights/maxItems"
        ).asInt()).isEqualTo(7);
        assertThat(responseSchema.at(
                "/properties/summaryBlocks/items/properties/evidenceRefs/items/enum"
        ))
                .extracting(JsonNode::toString)
                .asString()
                .contains(
                        "STORE.NET_REVENUE.CURRENT",
                        "EMP:E01.WORKLOAD.STATUS"
                );
        assertThat(responseSchema.at(
                "/properties/actions/maxItems"
        ).asInt()).isEqualTo(4);
        assertThat(responseSchema.at(
                "/properties/teamRelationships/maxItems"
        ).asInt()).isZero();
        assertThat(countDeclaredProperties(responseSchema)).isLessThanOrEqualTo(100);
        assertThat(prepared.request().responseSchemaJson())
                .doesNotContain(
                        "\"$ref\"",
                        "\"$defs\"",
                        "Required JSON shape"
                )
                .contains("\"anyOf\"");
        assertAllObjectPropertiesAreRequired(responseSchema);
    }

    @Test
    void buildsRevisedConcisePromptWithSmallerNarrativeBounds() throws IOException {
        WeeklySnapshotStore snapshotStore = mock(WeeklySnapshotStore.class);
        when(snapshotStore.findById(SNAPSHOT_ID)).thenReturn(Optional.of(snapshot()));
        LlmValidationRetryPromptFactory retryPromptFactory = mock(
                LlmValidationRetryPromptFactory.class
        );
        when(retryPromptFactory.appendRetryInstruction(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        LlmProviderRequestFactory factory = new LlmProviderRequestFactory(
                snapshotStore,
                retryPromptFactory,
                new LlmProviderInputCompactor()
        );

        PreparedLlmProviderRequest prepared = factory.prepare(
                job("weekly-interpretation-v6", 2),
                NOW,
                Duration.ofSeconds(90)
        );

        assertThat(prepared.request().systemPrompt()).contains(
                "Non-negotiable narrative safety",
                "Minimum useful response",
                "A `WORKLOAD` block is optional",
                "Avoid generic instructions"
        );
        JsonNode responseSchema = new ObjectMapper().readTree(
                prepared.request().responseSchemaJson()
        );
        assertThat(responseSchema.at(
                "/properties/summaryBlocks/minItems"
        ).asInt()).isEqualTo(3);
        assertThat(responseSchema.at(
                "/properties/summaryBlocks/maxItems"
        ).asInt()).isEqualTo(6);
        assertThat(responseSchema.at(
                "/properties/insights/maxItems"
        ).asInt()).isEqualTo(5);
        assertThat(responseSchema.at(
                "/properties/actions/maxItems"
        ).asInt()).isEqualTo(3);
    }

    @Test
    void buildsStrictCandidateBackedSchemaForV7() throws IOException {
        WeeklySnapshotStore snapshotStore = mock(WeeklySnapshotStore.class);
        when(snapshotStore.findById(SNAPSHOT_ID)).thenReturn(
                Optional.of(snapshotWithInsightCandidate())
        );
        LlmValidationRetryPromptFactory retryPromptFactory = mock(
                LlmValidationRetryPromptFactory.class
        );
        when(retryPromptFactory.appendRetryInstruction(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        LlmProviderRequestFactory factory = new LlmProviderRequestFactory(
                snapshotStore,
                retryPromptFactory,
                new LlmProviderInputCompactor()
        );

        PreparedLlmProviderRequest prepared = factory.prepare(
                job("weekly-interpretation-v7", 2),
                NOW,
                Duration.ofSeconds(90)
        );

        assertThat(prepared.request().systemPrompt()).contains(
                "Every insight must use one exact non-relationship",
                "Never create a free-form insight"
        );
        JsonNode responseSchema = new ObjectMapper().readTree(
                prepared.request().responseSchemaJson()
        );
        assertThat(responseSchema.at(
                "/properties/insights/maxItems"
        ).asInt()).isEqualTo(1);
        assertThat(responseSchema.at(
                "/properties/insights/items/properties/candidateRef/type"
        ).asText()).isEqualTo("string");
        assertThat(responseSchema.at(
                "/properties/insights/items/properties/candidateRef/enum"
        )).extracting(JsonNode::asText).containsExactly("C001");
        assertThat(responseSchema.at(
                "/properties/insights/items/properties/kind/enum"
        )).extracting(JsonNode::asText).containsExactly("RISK");
        assertThat(responseSchema.at(
                "/properties/insights/items/properties/theme/enum"
        )).extracting(JsonNode::asText)
                .containsExactly("REVENUE_DYNAMICS");
    }

    @Test
    void buildsActionableCandidateBackedSchemaForV8() throws IOException {
        WeeklySnapshotStore snapshotStore = mock(WeeklySnapshotStore.class);
        when(snapshotStore.findById(SNAPSHOT_ID)).thenReturn(
                Optional.of(snapshotWithInsightCandidate())
        );
        LlmValidationRetryPromptFactory retryPromptFactory = mock(
                LlmValidationRetryPromptFactory.class
        );
        when(retryPromptFactory.appendRetryInstruction(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        LlmProviderRequestFactory factory = new LlmProviderRequestFactory(
                snapshotStore,
                retryPromptFactory,
                new LlmProviderInputCompactor()
        );

        PreparedLlmProviderRequest prepared = factory.prepare(
                job("weekly-interpretation-v8", 2),
                NOW,
                Duration.ofSeconds(90)
        );

        assertThat(prepared.request().systemPrompt()).contains(
                "Every insight must use one exact non-relationship",
                "one observable management operation",
                "Merge the pair unless"
        );
        JsonNode responseSchema = new ObjectMapper().readTree(
                prepared.request().responseSchemaJson()
        );
        assertThat(responseSchema.at(
                "/properties/insights/maxItems"
        ).asInt()).isEqualTo(1);
        assertThat(responseSchema.at(
                "/properties/insights/items/properties/candidateRef/type"
        ).asText()).isEqualTo("string");
        assertThat(responseSchema.at(
                "/properties/insights/items/properties/candidateRef/enum"
        )).extracting(JsonNode::asText).containsExactly("C001");
        assertThat(responseSchema.at(
                "/properties/insights/items/properties/kind/enum"
        )).extracting(JsonNode::asText).containsExactly("RISK");
        assertThat(responseSchema.at(
                "/properties/insights/items/properties/theme/enum"
        )).extracting(JsonNode::asText)
                .containsExactly("REVENUE_DYNAMICS");
    }

    @Test
    void boundsEvidenceGuardedV12ActionsByNonRelationshipCandidates()
            throws IOException {
        WeeklySnapshotStore snapshotStore = mock(WeeklySnapshotStore.class);
        when(snapshotStore.findById(SNAPSHOT_ID)).thenReturn(
                Optional.of(snapshotWithInsightCandidate())
        );
        LlmValidationRetryPromptFactory retryPromptFactory = mock(
                LlmValidationRetryPromptFactory.class
        );
        when(retryPromptFactory.appendRetryInstruction(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        LlmProviderRequestFactory factory = new LlmProviderRequestFactory(
                snapshotStore,
                retryPromptFactory,
                new LlmProviderInputCompactor()
        );

        PreparedLlmProviderRequest prepared = factory.prepare(
                job("weekly-interpretation-v12", 2),
                NOW,
                Duration.ofSeconds(90)
        );

        assertThat(prepared.request().systemPrompt()).contains(
                "business dimensions of its own evidenceRefs",
                "authorizing at most one action",
                "literal vocabulary rule",
                "identical or near-identical",
                "Never use",
                "Insights are analysis only",
                "broader management area",
                "Summary blocks describe confirmed results only",
                "full insight title"
        );
        JsonNode responseSchema = new ObjectMapper().readTree(
                prepared.request().responseSchemaJson()
        );
        assertThat(responseSchema.at(
                "/properties/insights/maxItems"
        ).asInt()).isEqualTo(1);
        assertThat(responseSchema.at(
                "/properties/actions/maxItems"
        ).asInt()).isEqualTo(1);
    }

    @Test
    void forbidsV12ActionsWithoutNonRelationshipCandidates() throws IOException {
        WeeklySnapshotStore snapshotStore = mock(WeeklySnapshotStore.class);
        when(snapshotStore.findById(SNAPSHOT_ID)).thenReturn(
                Optional.of(snapshot())
        );
        LlmValidationRetryPromptFactory retryPromptFactory = mock(
                LlmValidationRetryPromptFactory.class
        );
        when(retryPromptFactory.appendRetryInstruction(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        LlmProviderRequestFactory factory = new LlmProviderRequestFactory(
                snapshotStore,
                retryPromptFactory,
                new LlmProviderInputCompactor()
        );

        PreparedLlmProviderRequest prepared = factory.prepare(
                job("weekly-interpretation-v12", 2),
                NOW,
                Duration.ofSeconds(90)
        );
        JsonNode responseSchema = new ObjectMapper().readTree(
                prepared.request().responseSchemaJson()
        );

        assertThat(responseSchema.at(
                "/properties/insights/maxItems"
        ).asInt()).isZero();
        assertThat(responseSchema.at(
                "/properties/actions/maxItems"
        ).asInt()).isZero();
    }

    @Test
    void keepsPluralEmployeeReferencesAsEmptyArraysWhenStoreHasNoEmployees()
            throws IOException {
        WeeklySnapshotStore snapshotStore = mock(WeeklySnapshotStore.class);
        when(snapshotStore.findById(SNAPSHOT_ID))
                .thenReturn(Optional.of(snapshotWithoutEmployees()));
        LlmValidationRetryPromptFactory retryPromptFactory = mock(
                LlmValidationRetryPromptFactory.class
        );
        when(retryPromptFactory.appendRetryInstruction(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        LlmProviderRequestFactory factory = new LlmProviderRequestFactory(
                snapshotStore,
                retryPromptFactory,
                new LlmProviderInputCompactor()
        );

        PreparedLlmProviderRequest prepared = factory.prepare(
                job("weekly-interpretation-v4", 2),
                NOW,
                Duration.ofSeconds(90)
        );
        JsonNode responseSchema = new ObjectMapper().readTree(
                prepared.request().responseSchemaJson()
        );

        for (String path : List.of(
                "/properties/actions/items/properties/targetEmployeeRefs",
                "/properties/teamRelationships/items/properties/sourceEmployeeRefs",
                "/properties/teamRelationships/items/properties/targetEmployeeRefs"
        )) {
            JsonNode references = responseSchema.at(path);
            assertThat(references.path("type").asText()).isEqualTo("array");
            assertThat(references.path("minItems").asInt()).isZero();
            assertThat(references.path("maxItems").asInt()).isZero();
        }
    }


    @Test
    void buildsV13SchemaWithOneBackendSelectedPrimarySignal()
            throws IOException {
        WeeklySnapshotStore snapshotStore = mock(WeeklySnapshotStore.class);
        when(snapshotStore.findById(SNAPSHOT_ID)).thenReturn(
                Optional.of(snapshotWithInsightCandidate())
        );
        LlmValidationRetryPromptFactory retryPromptFactory = mock(
                LlmValidationRetryPromptFactory.class
        );
        when(retryPromptFactory.appendRetryInstruction(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        LlmProviderRequestFactory factory = new LlmProviderRequestFactory(
                snapshotStore,
                retryPromptFactory,
                new LlmProviderInputCompactor()
        );

        PreparedLlmProviderRequest prepared = factory.prepare(
                job("weekly-interpretation-v13", 3),
                NOW,
                Duration.ofSeconds(90)
        );

        assertThat(prepared.request().systemPrompt()).contains(
                "WeeklyInterpretationContent v3",
                "single candidate-backed conclusion",
                "Never reuse the `primarySignal.candidateRef`"
        );
        JsonNode responseSchema = new ObjectMapper().readTree(
                prepared.request().responseSchemaJson()
        );
        assertThat(responseSchema.at(
                "/properties/summaryBlocks/minItems"
        ).asInt()).isEqualTo(2);
        assertThat(responseSchema.at(
                "/properties/primarySignal/anyOf/0/properties/"
                        + "candidateRef/enum"
        )).extracting(JsonNode::asText).containsExactly("C001");
        assertThat(responseSchema.at(
                "/properties/primarySignal/anyOf/0/properties/employeeRef/type"
        ).asText()).isEqualTo("null");
        assertThat(responseSchema.at(
                "/properties/primarySignal/anyOf/0/properties/"
                        + "evidenceRefs/items/enum"
        )).extracting(JsonNode::asText).contains(
                "STORE.NET_REVENUE.CURRENT"
        );
        assertThat(responseSchema.at(
                "/properties/insights/maxItems"
        ).asInt()).isZero();
        assertThat(responseSchema.at(
                "/properties/actions/maxItems"
        ).asInt()).isEqualTo(1);
        assertThat(responseSchema.path("properties").has("dataLimitations"))
                .isFalse();
    }

    @Test
    void buildsV15SchemaWithStructurallyRequiredSummaries()
            throws IOException {
        WeeklySnapshotStore snapshotStore = mock(WeeklySnapshotStore.class);
        when(snapshotStore.findById(SNAPSHOT_ID)).thenReturn(
                Optional.of(snapshotWithInsightCandidate())
        );
        LlmValidationRetryPromptFactory retryPromptFactory = mock(
                LlmValidationRetryPromptFactory.class
        );
        when(retryPromptFactory.appendRetryInstruction(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        LlmProviderRequestFactory factory = new LlmProviderRequestFactory(
                snapshotStore,
                retryPromptFactory,
                new LlmProviderInputCompactor()
        );

        PreparedLlmProviderRequest prepared = factory.prepare(
                job("weekly-interpretation-v15", 3),
                NOW,
                Duration.ofSeconds(90)
        );

        assertThat(prepared.request().systemPrompt()).contains(
                "provider response schema",
                "employeeHeadlines",
                "supportingSummaries",
                "Do not generate employees",
                "manifest scope is TEAM",
                "exactly one principal operation"
        );
        JsonNode responseSchema = new ObjectMapper().readTree(
                prepared.request().responseSchemaJson()
        );
        JsonNode properties = responseSchema.path("properties");
        assertThat(properties.has("employees")).isFalse();
        assertThat(properties.has("summaryBlocks")).isFalse();
        assertThat(properties.has("dataLimitations")).isFalse();
        assertThat(properties.has("teamOverview")).isTrue();
        assertThat(properties.has("employeeHeadlines")).isTrue();
        assertThat(properties.has("supportingSummaries")).isTrue();
        assertThat(responseSchema.path("required"))
                .extracting(JsonNode::asText)
                .contains(
                        "teamOverview",
                        "employeeHeadlines",
                        "supportingSummaries"
                )
                .doesNotContain("employees", "summaryBlocks");
        assertThat(responseSchema.at(
                "/properties/employeeHeadlines/required"
        )).extracting(JsonNode::asText).containsExactly("E01");
        assertThat(responseSchema.at(
                "/properties/employeeHeadlines/properties/E01/"
                        + "properties/evidenceRefs/items/enum"
        )).extracting(JsonNode::asText).contains(
                "EMP:E01.WORKLOAD.STATUS"
        );
        assertThat(responseSchema.at(
                "/properties/teamOverview/properties/"
                        + "evidenceRefs/items/enum"
        )).extracting(JsonNode::asText)
                .containsExactly("TEAM.RATING.ELIGIBLE_COUNT");
        assertThat(responseSchema.at(
                "/properties/supportingSummaries/minItems"
        ).asInt()).isZero();
        assertThat(responseSchema.at(
                "/properties/supportingSummaries/maxItems"
        ).asInt()).isEqualTo(4);
        assertThat(responseSchema.at(
                "/properties/supportingSummaries/items/properties/section/enum"
        )).extracting(JsonNode::asText)
                .containsExactly(
                        "WORKLOAD",
                        "RESULT",
                        "DYNAMICS",
                        "CATEGORY_PERFORMANCE",
                        "ADDITIONAL_SALES",
                        "PLAN_OUTLOOK"
                )
                .doesNotContain("HEADLINE", "TEAM_OVERVIEW");
        assertThat(responseSchema.at(
                "/properties/primarySignal/anyOf/0/properties/"
                        + "candidateRef/enum"
        )).extracting(JsonNode::asText).containsExactly("C001");
        assertAllObjectPropertiesAreRequired(responseSchema);
        assertThat(countDeclaredProperties(responseSchema))
                .isLessThanOrEqualTo(100);
    }

    @Test
    void buildsV16SchemaWithScopedHeadlinesAndBoundedActions()
            throws IOException {
        WeeklySnapshotStore snapshotStore = mock(WeeklySnapshotStore.class);
        when(snapshotStore.findById(SNAPSHOT_ID)).thenReturn(
                Optional.of(snapshotWithInsightCandidate())
        );
        LlmValidationRetryPromptFactory retryPromptFactory = mock(
                LlmValidationRetryPromptFactory.class
        );
        when(retryPromptFactory.appendRetryInstruction(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        LlmProviderRequestFactory factory = new LlmProviderRequestFactory(
                snapshotStore,
                retryPromptFactory,
                new LlmProviderInputCompactor()
        );

        PreparedLlmProviderRequest prepared = factory.prepare(
                job("weekly-interpretation-v16", 3),
                NOW,
                Duration.ofSeconds(90)
        );

        assertThat(prepared.request().systemPrompt()).contains(
                "routing key such as `E01`",
                "Treat each candidate-backed narrative as an isolated task",
                "safe default is an empty `actions` array",
                "Return exactly one relationship for every relationship candidate"
        );
        JsonNode responseSchema = new ObjectMapper().readTree(
                prepared.request().responseSchemaJson()
        );
        assertThat(responseSchema.at(
                "/properties/employeeHeadlines/properties/E01/"
                        + "properties/evidenceRefs/items/enum"
        )).extracting(JsonNode::asText)
                .containsExactly("EMP:E01.WORKLOAD.STATUS");
        assertThat(responseSchema.at(
                "/properties/actions/maxItems"
        ).asInt()).isEqualTo(1);
        assertThat(responseSchema.at(
                "/properties/teamRelationships/minItems"
        ).asInt()).isZero();
        assertThat(responseSchema.at(
                "/properties/teamRelationships/maxItems"
        ).asInt()).isZero();
        assertAllObjectPropertiesAreRequired(responseSchema);
    }

    @Test
    void buildsV16SchemaWithExactSingleRelationshipCandidate()
            throws IOException {
        WeeklyInterpretationInput.CandidateSignal relationship =
                new WeeklyInterpretationInput.CandidateSignal(
                        "C001",
                        WeeklyInterpretationInput.CandidateKind.OPPORTUNITY,
                        "MOST_IMPROVED",
                        "E01",
                        null,
                        null,
                        List.of(),
                        WeeklyInterpretationInput.Sufficiency.SUFFICIENT,
                        List.of("EMP:E01.WORKLOAD.STATUS")
                );
        WeeklySnapshotStore snapshotStore = mock(WeeklySnapshotStore.class);
        when(snapshotStore.findById(SNAPSHOT_ID)).thenReturn(
                Optional.of(snapshotWithInsightCandidates(
                        List.of(relationship)
                ))
        );
        LlmValidationRetryPromptFactory retryPromptFactory = mock(
                LlmValidationRetryPromptFactory.class
        );
        when(retryPromptFactory.appendRetryInstruction(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        LlmProviderRequestFactory factory = new LlmProviderRequestFactory(
                snapshotStore,
                retryPromptFactory,
                new LlmProviderInputCompactor()
        );

        JsonNode schema = new ObjectMapper().readTree(
                factory.prepare(
                        job("weekly-interpretation-v16", 3),
                        NOW,
                        Duration.ofSeconds(90)
                ).request().responseSchemaJson()
        );

        assertThat(schema.at(
                "/properties/teamRelationships/minItems"
        ).asInt()).isOne();
        assertThat(schema.at(
                "/properties/teamRelationships/maxItems"
        ).asInt()).isOne();
        assertThat(schema.at(
                "/properties/teamRelationships/items/properties/type/enum"
        )).extracting(JsonNode::asText).containsExactly("MOST_IMPROVED");
        assertThat(schema.at(
                "/properties/teamRelationships/items/properties/"
                        + "sourceEmployeeRefs/items/enum"
        )).extracting(JsonNode::asText).containsExactly("E01");
        assertThat(schema.at(
                "/properties/teamRelationships/items/properties/"
                        + "targetEmployeeRefs/maxItems"
        ).asInt()).isZero();
        assertThat(schema.at(
                "/properties/teamRelationships/items/properties/summary/enum"
        )).extracting(JsonNode::asText).containsExactly(
                "Подтверждена наиболее заметная положительная динамика "
                        + "среди сопоставимых сотрудников."
        );
    }

    @Test
    void buildsV17SchemaWithBackendOwnedTeamTextAndRelationships()
            throws IOException {
        WeeklyInterpretationInput.CandidateSignal relationship =
                new WeeklyInterpretationInput.CandidateSignal(
                        "C001",
                        WeeklyInterpretationInput.CandidateKind.OPPORTUNITY,
                        "MOST_IMPROVED",
                        "E01",
                        null,
                        null,
                        List.of(),
                        WeeklyInterpretationInput.Sufficiency.SUFFICIENT,
                        List.of("EMP:E01.WORKLOAD.STATUS")
                );
        WeeklySnapshotStore snapshotStore = mock(WeeklySnapshotStore.class);
        when(snapshotStore.findById(SNAPSHOT_ID)).thenReturn(
                Optional.of(snapshotWithInsightCandidates(
                        List.of(relationship)
                ))
        );
        LlmValidationRetryPromptFactory retryPromptFactory = mock(
                LlmValidationRetryPromptFactory.class
        );
        when(retryPromptFactory.appendRetryInstruction(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        LlmProviderRequestFactory factory = new LlmProviderRequestFactory(
                snapshotStore,
                retryPromptFactory,
                new LlmProviderInputCompactor()
        );

        PreparedLlmProviderRequest prepared = factory.prepare(
                job("weekly-interpretation-v17", 3),
                NOW,
                Duration.ofSeconds(90)
        );
        JsonNode schema = new ObjectMapper().readTree(
                prepared.request().responseSchemaJson()
        );

        assertThat(prepared.request().systemPrompt()).contains(
                "Return an empty `teamRelationships` array",
                "text allowed by the supplied schema"
        );
        assertThat(schema.at(
                "/properties/teamOverview/properties/text/enum"
        )).extracting(JsonNode::asText).containsExactly(
                "Сопоставление сотрудников ограничено недостаточной "
                        + "командной базой."
        );
        assertThat(schema.at(
                "/properties/teamRelationships/minItems"
        ).asInt()).isZero();
        assertThat(schema.at(
                "/properties/teamRelationships/maxItems"
        ).asInt()).isZero();
        assertAllObjectPropertiesAreRequired(schema);
    }

    @Test
    void buildsV18SchemaWithDeterministicCandidateNarratives()
            throws IOException {
        WeeklySnapshotStore snapshotStore = mock(WeeklySnapshotStore.class);
        when(snapshotStore.findById(SNAPSHOT_ID)).thenReturn(
                Optional.of(snapshotWithInsightCandidates(List.of(
                        insightCandidate(
                                "C001",
                                WeeklyInterpretationInput.CandidateKind.RISK,
                                "REVENUE_DYNAMICS"
                        ),
                        insightCandidate(
                                "C002",
                                WeeklyInterpretationInput.CandidateKind.OPPORTUNITY,
                                "CATEGORY_MIX"
                        )
                )))
        );
        LlmValidationRetryPromptFactory retryPromptFactory = mock(
                LlmValidationRetryPromptFactory.class
        );
        when(retryPromptFactory.appendRetryInstruction(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        LlmProviderRequestFactory factory = new LlmProviderRequestFactory(
                snapshotStore,
                retryPromptFactory,
                new LlmProviderInputCompactor()
        );

        PreparedLlmProviderRequest prepared = factory.prepare(
                job("weekly-interpretation-v18", 3),
                NOW,
                Duration.ofSeconds(90)
        );
        JsonNode schema = new ObjectMapper().readTree(
                prepared.request().responseSchemaJson()
        );

        assertThat(prepared.request().systemPrompt()).contains(
                "backend reconstructs those narratives",
                "use exactly an allowed enum value"
        );
        assertThat(schema.at(
                "/properties/primarySignal/anyOf/0/properties/text/enum"
        )).extracting(JsonNode::asText).containsExactly(
                "Динамика выручки требует внимания."
        );
        assertThat(schema.at(
                "/properties/insights/items/properties/title/enum"
        )).extracting(JsonNode::asText).containsExactly(
                "Динамика категории"
        );
        assertThat(schema.at(
                "/properties/insights/items/properties/summary/enum"
        )).extracting(JsonNode::asText).containsExactly(
                "Динамика категории: подтверждён положительный сигнал."
        );
        assertAllObjectPropertiesAreRequired(schema);
    }

    @Test
    void buildsV19StoreOnlyRequestWithBackendOwnedEmployeeMarker()
            throws IOException {
        WeeklySnapshotStore snapshotStore = mock(WeeklySnapshotStore.class);
        when(snapshotStore.findById(SNAPSHOT_ID)).thenReturn(
                Optional.of(snapshotWithInsightCandidates(List.of(
                        insightCandidate(
                                "C001",
                                WeeklyInterpretationInput.CandidateKind.RISK,
                                "REVENUE_DYNAMICS"
                        ),
                        insightCandidate(
                                "C002",
                                WeeklyInterpretationInput.CandidateKind.OPPORTUNITY,
                                "CATEGORY_MIX"
                        )
                )))
        );
        LlmValidationRetryPromptFactory retryPromptFactory = mock(
                LlmValidationRetryPromptFactory.class
        );
        when(retryPromptFactory.appendRetryInstruction(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        LlmProviderRequestFactory factory = new LlmProviderRequestFactory(
                snapshotStore,
                retryPromptFactory,
                new LlmProviderInputCompactor()
        );

        PreparedLlmProviderRequest prepared = factory.prepare(
                job("weekly-interpretation-v19", 3),
                NOW,
                Duration.ofSeconds(90)
        );
        ObjectMapper mapper = new ObjectMapper();
        JsonNode input = mapper.readTree(prepared.request().inputJson());
        JsonNode schema = mapper.readTree(
                prepared.request().responseSchemaJson()
        );

        assertThat(prepared.request().systemPrompt()).contains(
                "backendEmployeeHeadlines",
                "aggregated retail"
        );
        assertThat(input.at("/manifest/employeeRefs")).isEmpty();
        assertThat(input.at("/facts/employees")).isEmpty();
        assertThat(input.at("/manifest/competencyCodes")).isEmpty();
        assertThat(schema.at(
                "/properties/backendEmployeeHeadlines/enum/0"
        ).asBoolean()).isTrue();
        assertThat(schema.at("/properties/employeeHeadlines").isMissingNode())
                .isTrue();
        assertThat(schema.at(
                "/properties/teamRelationships/maxItems"
        ).asInt()).isZero();
        assertAllObjectPropertiesAreRequired(schema);
    }

    @Test
    void keepsV15SchemaWithinProviderPropertyBudgetForNineEmployees()
            throws IOException {
        WeeklySnapshotStore snapshotStore = mock(WeeklySnapshotStore.class);
        when(snapshotStore.findById(SNAPSHOT_ID)).thenReturn(
                Optional.of(snapshotWithEmployeeCount(9))
        );
        LlmValidationRetryPromptFactory retryPromptFactory = mock(
                LlmValidationRetryPromptFactory.class
        );
        when(retryPromptFactory.appendRetryInstruction(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        LlmProviderRequestFactory factory = new LlmProviderRequestFactory(
                snapshotStore,
                retryPromptFactory,
                new LlmProviderInputCompactor()
        );

        PreparedLlmProviderRequest prepared = factory.prepare(
                job("weekly-interpretation-v15", 3),
                NOW,
                Duration.ofSeconds(90)
        );
        JsonNode responseSchema = new ObjectMapper().readTree(
                prepared.request().responseSchemaJson()
        );

        assertThat(responseSchema.at(
                "/properties/employeeHeadlines/required"
        )).hasSize(9);
        assertThat(responseSchema.at(
                "/properties/employeeHeadlines/properties"
        )).hasSize(9);
        assertThat(countDeclaredProperties(responseSchema))
                .isLessThanOrEqualTo(100);
    }

    @Test
    void excludesPrimaryCandidateFromSecondaryInsightEnum()
            throws IOException {
        WeeklySnapshotStore snapshotStore = mock(WeeklySnapshotStore.class);
        when(snapshotStore.findById(SNAPSHOT_ID)).thenReturn(Optional.of(
                snapshotWithInsightCandidates(List.of(
                        insightCandidate(
                                "C001",
                                WeeklyInterpretationInput.CandidateKind.RISK,
                                "REVENUE_DYNAMICS"
                        ),
                        insightCandidate(
                                "C002",
                                WeeklyInterpretationInput.CandidateKind.OPPORTUNITY,
                                "ADDITIONAL_SALES"
                        )
                ))
        ));
        LlmValidationRetryPromptFactory retryPromptFactory = mock(
                LlmValidationRetryPromptFactory.class
        );
        when(retryPromptFactory.appendRetryInstruction(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        LlmProviderRequestFactory factory = new LlmProviderRequestFactory(
                snapshotStore,
                retryPromptFactory,
                new LlmProviderInputCompactor()
        );

        PreparedLlmProviderRequest prepared = factory.prepare(
                job("weekly-interpretation-v13", 3),
                NOW,
                Duration.ofSeconds(90)
        );
        JsonNode responseSchema = new ObjectMapper().readTree(
                prepared.request().responseSchemaJson()
        );

        assertThat(responseSchema.at(
                "/properties/primarySignal/anyOf/0/properties/"
                        + "candidateRef/enum"
        )).extracting(JsonNode::asText).containsExactly("C001");
        assertThat(responseSchema.at(
                "/properties/insights/maxItems"
        ).asInt()).isEqualTo(1);
        assertThat(responseSchema.at(
                "/properties/insights/items/properties/candidateRef/enum"
        )).extracting(JsonNode::asText).containsExactly("C002");
        assertThat(responseSchema.at(
                "/properties/insights/items/properties/kind/enum"
        )).extracting(JsonNode::asText).containsExactly("OPPORTUNITY");
    }

    @Test
    void rejectsMixedPromptAndSchemaVersionsBeforeSnapshotRead() {
        WeeklySnapshotStore snapshotStore = mock(WeeklySnapshotStore.class);
        LlmProviderRequestFactory factory = new LlmProviderRequestFactory(
                snapshotStore,
                mock(LlmValidationRetryPromptFactory.class),
                new LlmProviderInputCompactor()
        );

        assertThatThrownBy(() -> factory.prepare(
                job("weekly-interpretation-v3", 2),
                NOW,
                Duration.ofSeconds(90)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a packaged pair");
    }


    private PersistedWeeklySnapshot snapshotWithInsightCandidate()
            throws IOException {
        return snapshotWithInsightCandidates(List.of(insightCandidate(
                "C001",
                WeeklyInterpretationInput.CandidateKind.RISK,
                "REVENUE_DYNAMICS"
        )));
    }

    private WeeklyInterpretationInput.CandidateSignal insightCandidate(
            String candidateRef,
            WeeklyInterpretationInput.CandidateKind kind,
            String theme
    ) {
        return new WeeklyInterpretationInput.CandidateSignal(
                candidateRef,
                kind,
                theme,
                null,
                null,
                null,
                List.of(),
                WeeklyInterpretationInput.Sufficiency.SUFFICIENT,
                List.of("STORE.NET_REVENUE.CURRENT")
        );
    }

    private PersistedWeeklySnapshot snapshotWithInsightCandidates(
            List<WeeklyInterpretationInput.CandidateSignal> candidates
    ) throws IOException {
        PersistedWeeklySnapshot source = snapshot();
        WeeklySnapshotPayload payload = source.payload();
        WeeklyInterpretationInput.Manifest manifest = payload.manifest();
        WeeklyInterpretationInput.Facts facts = payload.facts();
        WeeklySnapshotPayload updatedPayload = new WeeklySnapshotPayload(
                payload.contractVersion(),
                new WeeklyInterpretationInput.Manifest(
                        manifest.employeeRefs(),
                        manifest.evidence(),
                        candidates.stream()
                                .map(WeeklyInterpretationInput.CandidateSignal
                                        ::candidateRef)
                                .toList(),
                        manifest.categoryCodes(),
                        manifest.categoryLabels(),
                        manifest.competencyCodes(),
                        manifest.limitations()
                ),
                new WeeklyInterpretationInput.Facts(
                        facts.store(),
                        facts.team(),
                        facts.employees(),
                        candidates
                )
        );
        return new PersistedWeeklySnapshot(
                source.id(),
                source.storeId(),
                source.query(),
                source.timezone(),
                source.revision(),
                source.supersedesSnapshotId(),
                source.revisionReasonCode(),
                source.revisionNote(),
                source.sourceSyncJobId(),
                source.sourceSyncCompletedAt(),
                source.sourceDataCutoff(),
                source.qualityStatus(),
                source.versions(),
                updatedPayload,
                source.factsHash(),
                source.employees(),
                source.createdAt()
        );
    }

    private PersistedWeeklySnapshot snapshotWithEmployeeCount(int count)
            throws IOException {
        PersistedWeeklySnapshot source = snapshot();
        WeeklySnapshotPayload payload = source.payload();
        WeeklyInterpretationInput.Manifest manifest = payload.manifest();
        List<String> employeeRefs = new ArrayList<>();
        List<WeeklyInterpretationInput.EvidenceIndexEntry> evidence =
                new ArrayList<>();
        manifest.evidence().stream()
                .filter(entry -> entry.scope()
                        != WeeklyInterpretationInput.Scope.EMPLOYEE)
                .forEach(evidence::add);
        List<WeeklyInterpretationInput.EmployeeFacts> employees =
                new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            String employeeRef = String.format("E%02d", index);
            String evidenceRef = "EMP:" + employeeRef + ".WORKLOAD.STATUS";
            employeeRefs.add(employeeRef);
            evidence.add(new WeeklyInterpretationInput.EvidenceIndexEntry(
                    evidenceRef,
                    WeeklyInterpretationInput.Scope.EMPLOYEE,
                    employeeRef,
                    true
            ));
            employees.add(new WeeklyInterpretationInput.EmployeeFacts(
                    employeeRef,
                    WeeklyInterpretationInput.Sufficiency.SUFFICIENT,
                    List.of("RESULT"),
                    List.of(new WeeklyInterpretationInput.Fact(
                            evidenceRef,
                            "WORKLOAD_STATUS",
                            null,
                            WeeklyInterpretationInput.Unit.STATUS,
                            "SUFFICIENT",
                            null,
                            WeeklyInterpretationInput.Sufficiency.SUFFICIENT,
                            WeeklyInterpretationInput.Materiality.CONTEXT
                    ))
            ));
        }
        WeeklySnapshotPayload updatedPayload = new WeeklySnapshotPayload(
                payload.contractVersion(),
                new WeeklyInterpretationInput.Manifest(
                        employeeRefs,
                        evidence,
                        manifest.candidateRefs(),
                        manifest.categoryCodes(),
                        manifest.categoryLabels(),
                        manifest.competencyCodes(),
                        manifest.limitations()
                ),
                new WeeklyInterpretationInput.Facts(
                        payload.facts().store(),
                        payload.facts().team(),
                        employees,
                        payload.facts().candidateSignals()
                )
        );
        return new PersistedWeeklySnapshot(
                source.id(),
                source.storeId(),
                source.query(),
                source.timezone(),
                source.revision(),
                source.supersedesSnapshotId(),
                source.revisionReasonCode(),
                source.revisionNote(),
                source.sourceSyncJobId(),
                source.sourceSyncCompletedAt(),
                source.sourceDataCutoff(),
                source.qualityStatus(),
                source.versions(),
                updatedPayload,
                source.factsHash(),
                source.employees(),
                source.createdAt()
        );
    }

    private PersistedWeeklySnapshot snapshotWithoutEmployees() throws IOException {
        WeeklyInterpretationInput source = new ObjectMapper().readValue(
                resource("contracts/llm/examples/weekly-interpretation-input-v1-minimal.json"),
                WeeklyInterpretationInput.class
        );
        WeeklyInterpretationInput.Manifest manifest = source.manifest();
        WeeklyInterpretationInput.Facts facts = source.facts();
        WeeklyInterpretationInput example = new WeeklyInterpretationInput(
                source.contractVersion(),
                source.snapshot(),
                new WeeklyInterpretationInput.Manifest(
                        List.of(),
                        manifest.evidence(),
                        manifest.candidateRefs(),
                        manifest.categoryCodes(),
                        manifest.competencyCodes(),
                        manifest.limitations()
                ),
                new WeeklyInterpretationInput.Facts(
                        facts.store(), facts.team(), List.of(), facts.candidateSignals()
                )
        );
        WeeklyInterpretationInput.Snapshot header = example.snapshot();
        LocalDate start = header.period().start();
        LocalDate end = header.period().end();
        WeeklyAnalyticsFactsQuery query = new WeeklyAnalyticsFactsQuery(
                UUID.randomUUID(),
                new StoreKpiPeriod(start, end),
                new StoreKpiPeriod(
                        header.comparisonPeriod().start(),
                        header.comparisonPeriod().end()
                )
        );
        return new PersistedWeeklySnapshot(
                SNAPSHOT_ID,
                query.storeId(),
                query,
                header.timezone(),
                header.revision(),
                null,
                "INITIAL",
                null,
                UUID.randomUUID(),
                NOW.minusSeconds(60),
                NOW.minusSeconds(60),
                header.qualityStatus(),
                header.versions(),
                new WeeklySnapshotPayload(
                        example.contractVersion(),
                        example.manifest(),
                        example.facts()
                ),
                header.factsHash(),
                List.of(),
                NOW.minusSeconds(60)
        );
    }

    private PersistedWeeklySnapshot snapshot() throws IOException {
        WeeklyInterpretationInput example = new ObjectMapper().readValue(
                resource("contracts/llm/examples/weekly-interpretation-input-v1-minimal.json"),
                WeeklyInterpretationInput.class
        );
        WeeklyInterpretationInput.Snapshot header = example.snapshot();
        LocalDate start = header.period().start();
        LocalDate end = header.period().end();
        WeeklyAnalyticsFactsQuery query = new WeeklyAnalyticsFactsQuery(
                UUID.randomUUID(),
                new StoreKpiPeriod(start, end),
                new StoreKpiPeriod(
                        header.comparisonPeriod().start(),
                        header.comparisonPeriod().end()
                )
        );
        return new PersistedWeeklySnapshot(
                SNAPSHOT_ID,
                query.storeId(),
                query,
                header.timezone(),
                header.revision(),
                null,
                "INITIAL",
                null,
                UUID.randomUUID(),
                NOW.minusSeconds(60),
                NOW.minusSeconds(60),
                header.qualityStatus(),
                header.versions(),
                new WeeklySnapshotPayload(
                        example.contractVersion(),
                        example.manifest(),
                        example.facts()
                ),
                header.factsHash(),
                List.of(),
                NOW.minusSeconds(60)
        );
    }

    private LlmAnalysisJob job() {
        return job("weekly-interpretation-v3", 1);
    }

    private LlmAnalysisJob job(
            String promptVersion,
            int contentSchemaVersion
    ) {
        LlmAnalysisJob job = mock(LlmAnalysisJob.class);
        when(job.id()).thenReturn(UUID.randomUUID());
        when(job.snapshotId()).thenReturn(SNAPSHOT_ID);
        when(job.providerCode()).thenReturn("YANDEX");
        when(job.requestedModel()).thenReturn("gpt://folder/yandexgpt/latest");
        when(job.promptVersion()).thenReturn(promptVersion);
        when(job.contentSchemaVersion()).thenReturn(contentSchemaVersion);
        when(job.generationParameters()).thenReturn(
                "{\"temperature\":0.2,\"maxOutputTokens\":4000,"
                        + "\"maxProviderCalls\":2}"
        );
        when(job.deadlineAt()).thenReturn(NOW.plus(Duration.ofMinutes(5)));
        return job;
    }

    private int countDeclaredProperties(JsonNode node) {
        int count = node.path("properties").size();
        for (JsonNode child : node) {
            count += countDeclaredProperties(child);
        }
        return count;
    }

    private void assertAllObjectPropertiesAreRequired(JsonNode node) {
        if (node.path("properties").isObject()) {
            List<String> properties = node.path("properties").properties()
                    .stream()
                    .map(java.util.Map.Entry::getKey)
                    .sorted()
                    .toList();
            List<String> required = new ArrayList<>();
            node.path("required").forEach(value -> required.add(value.asText()));
            assertThat(required).containsExactlyInAnyOrderElementsOf(properties);
        }
        for (JsonNode child : node) {
            assertAllObjectPropertiesAreRequired(child);
        }
    }


    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("Missing resource: " + path);
            }
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
