package com.storeanalytics.interpretation.review.ai;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.storeanalytics.integration.llm.yandex.YandexLlmProperties;
import com.storeanalytics.interpretation.generation.LlmProviderRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class WeeklyReviewAiConfigurationReadinessTest {

    @Test
    void disabledParentRejectsEnabledPlannerOrWorker() {
        assertThatThrownBy(() -> readiness(
                WeeklyReviewAiTestProperties.properties(false, true, false),
                yandex(true),
                mock(LlmProviderRegistry.class)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("parent feature");
    }

    @Test
    void enabledFeatureRequiresVersionedProviderConfiguration() {
        assertThatThrownBy(() -> readiness(
                WeeklyReviewAiTestProperties.properties(true, false, false),
                yandex(false),
                mock(LlmProviderRegistry.class)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incomplete");
    }

    @Test
    void enabledWorkerRequiresRegisteredApprovedProvider() {
        LlmProviderRegistry registry = mock(LlmProviderRegistry.class);

        assertThatCode(() -> readiness(
                WeeklyReviewAiTestProperties.properties(true, false, true),
                yandex(true),
                registry
        )).doesNotThrowAnyException();
        verify(registry).requireProvider("YANDEX");
    }

    private WeeklyReviewAiConfigurationReadiness readiness(
            WeeklyReviewAiGenerationProperties properties,
            YandexLlmProperties yandex,
            LlmProviderRegistry registry
    ) {
        return new WeeklyReviewAiConfigurationReadiness(
                properties, yandex, registry
        );
    }

    private YandexLlmProperties yandex(boolean configured) {
        return new YandexLlmProperties(
                configured ? "folder" : "",
                configured ? "secret" : "",
                configured ? "gpt://folder/yandexgpt-5.1" : "",
                Duration.ofSeconds(5),
                Duration.ofMinutes(3)
        );
    }
}
