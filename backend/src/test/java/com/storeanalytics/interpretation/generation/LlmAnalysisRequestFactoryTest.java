package com.storeanalytics.interpretation.generation;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.integration.llm.yandex.YandexLlmProperties;
import com.storeanalytics.interpretation.config.LlmAnalysisPlannerProperties;
import com.storeanalytics.interpretation.config.LlmGenerationProperties;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LlmAnalysisRequestFactoryTest {

    private static final Instant NOW = Instant.parse("2026-08-03T05:00:00Z");

    @Test
    void createsDeterministicVersionedRequestWithoutSecrets() {
        LlmAnalysisRequestFactory factory = factory("gpt://folder/yandexgpt/latest");
        LlmAnalysisPlanningStore.SnapshotTarget target = target(1, "a".repeat(64));

        LlmAnalysisJobRequest first = factory.automatic(target, NOW);
        LlmAnalysisJobRequest second = factory.automatic(target, NOW);

        assertThat(first).isEqualTo(second);
        assertThat(first.triggerType()).isEqualTo(LlmAnalysisTriggerType.INITIAL);
        assertThat(first.providerCode()).isEqualTo("YANDEX");
        assertThat(first.generationRevision()).isOne();
        assertThat(first.inputHash()).matches("[a-f0-9]{64}");
        assertThat(first.generationParameters())
                .contains("\"maxOutputTokens\":4000")
                .contains("\"maxProviderCalls\":2")
                .contains("\"temperature\":0.2")
                .doesNotContain("secret");
        assertThat(first.deadlineAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
    }

    @Test
    void changesHashForFactsOrModelAndMarksSnapshotRevision() {
        LlmAnalysisJobRequest base = factory("gpt://folder/yandexgpt/latest")
                .automatic(target(1, "a".repeat(64)), NOW);
        LlmAnalysisJobRequest changedFacts = factory("gpt://folder/yandexgpt/latest")
                .automatic(target(2, "b".repeat(64)), NOW);
        LlmAnalysisJobRequest changedModel = factory("gpt://folder/yandexgpt-pro/latest")
                .automatic(target(1, "a".repeat(64)), NOW);

        assertThat(changedFacts.triggerType())
                .isEqualTo(LlmAnalysisTriggerType.SNAPSHOT_REVISION);
        assertThat(changedFacts.inputHash()).isNotEqualTo(base.inputHash());
        assertThat(changedModel.inputHash()).isNotEqualTo(base.inputHash());
    }

    private LlmAnalysisRequestFactory factory(String modelUri) {
        return new LlmAnalysisRequestFactory(
                new LlmGenerationProperties(
                        "weekly-interpretation-v3",
                        1,
                        new BigDecimal("0.2"),
                        4000,
                        2
                ),
                new YandexLlmProperties(
                        "folder",
                        "secret",
                        modelUri,
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(90)
                ),
                new LlmAnalysisPlannerProperties(
                        true,
                        Duration.ofMinutes(1),
                        25,
                        Duration.ofMinutes(5)
                )
        );
    }

    private LlmAnalysisPlanningStore.SnapshotTarget target(
            int revision,
            String factsHash
    ) {
        return new LlmAnalysisPlanningStore.SnapshotTarget(
                UUID.fromString("14191bf6-40c5-43f5-afb9-cd68ad6c30d0"),
                UUID.fromString("848e9adb-8174-4c09-840e-05064c24112d"),
                revision,
                "READY",
                factsHash,
                NOW.minusSeconds(60)
        );
    }
}
