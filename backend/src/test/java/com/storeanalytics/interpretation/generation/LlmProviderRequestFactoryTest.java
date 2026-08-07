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
        assertThat(input.at("/manifest/evidence").isEmpty()).isTrue();
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
                "/properties/actions/maxItems"
        ).asInt()).isEqualTo(4);
        assertThat(responseSchema.at(
                "/properties/teamRelationships/maxItems"
        ).asInt()).isEqualTo(4);
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
