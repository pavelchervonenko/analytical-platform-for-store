package com.storeanalytics.integration.llm.yandex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.interpretation.generation.LlmProviderRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class YandexLlmRequestPreflightTest {

    private static final String MODEL = "gpt://folder/yandexgpt-5.1";

    @Test
    void evaluatesRequestWithoutApiCredentialOrNetwork() {
        YandexLlmRequestPreflight evaluator = evaluator(MODEL);

        var result = evaluator.evaluate(request(MODEL));

        assertThat(result.estimatedInputTokens()).isPositive();
        assertThat(result.contextWindowTokens()).isEqualTo(32_768);
        assertThat(result.estimatedMaximumCost()).isPositive();
        assertThat(result.costCurrency()).isEqualTo("RUB");
    }

    @Test
    void rejectsMutableOrMismatchedModelConfiguration() {
        assertThatThrownBy(() -> evaluator(
                "gpt://folder/yandexgpt/latest"
        ).evaluate(request("gpt://folder/yandexgpt/latest")))
                .isInstanceOf(YandexLlmProviderException.class)
                .hasMessageNotContaining("folder");

        assertThatThrownBy(() -> evaluator(MODEL).evaluate(request(
                "gpt://different/yandexgpt-5.1"
        ))).isInstanceOf(YandexLlmProviderException.class);
    }

    private YandexLlmRequestPreflight evaluator(String configuredModel) {
        return new YandexLlmRequestPreflight(
                new YandexLlmProperties(
                        "folder",
                        "",
                        configuredModel,
                        Duration.ofSeconds(5),
                        Duration.ofMinutes(3)
                ),
                new YandexLlmPolicyProperties(
                        32_768,
                        1_048_576,
                        new BigDecimal("0.8"),
                        new BigDecimal("0.8"),
                        new BigDecimal("0.8")
                ),
                new ObjectMapper(),
                new YandexLlmMetrics(new SimpleMeterRegistry())
        );
    }

    private LlmProviderRequest request(String model) {
        return new LlmProviderRequest(
                UUID.randomUUID(),
                "YANDEX",
                model,
                "system prompt",
                "{}",
                "{\"type\":\"object\"}",
                new BigDecimal("0.1"),
                1400,
                Instant.parse("2026-09-15T12:00:00Z")
        );
    }
}
