package com.storeanalytics.interpretation.generation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.interpretation.config.LlmAnalysisWorkerProperties;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LlmProviderBudgetGuardTest {

    private final LlmProviderBudgetGuard guard = new LlmProviderBudgetGuard(
            new LlmAnalysisWorkerProperties(
                    false,
                    Duration.ofSeconds(5),
                    Duration.ofMinutes(2),
                    Duration.ofSeconds(15),
                    Duration.ofSeconds(30),
                    Duration.ofSeconds(90),
                    16_384,
                    new BigDecimal("5.00")
            )
    );

    @Test
    void enforcesRequestContextCurrencyAndCostBudgets() {
        LlmProviderRequest request = request("input", 4_000);
        assertThatCode(() -> guard.validate(
                request,
                new LlmProviderPreflight(1_000, 8_000, new BigDecimal("4.99"), "RUB")
        )).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.validate(
                request,
                new LlmProviderPreflight(5_000, 8_000, new BigDecimal("4.99"), "RUB")
        )).isInstanceOf(LlmProviderPreflightException.class)
                .hasMessageContaining("context window");
        assertThatThrownBy(() -> guard.validate(
                request,
                new LlmProviderPreflight(1_000, 8_000, new BigDecimal("5.01"), "RUB")
        )).isInstanceOf(LlmProviderPreflightException.class)
                .hasMessageContaining("cost budget");
        assertThatThrownBy(() -> guard.validate(
                request,
                new LlmProviderPreflight(1_000, 8_000, new BigDecimal("1.00"), "USD")
        )).isInstanceOf(LlmProviderPreflightException.class)
                .hasMessageContaining("currency");
        assertThatThrownBy(() -> guard.validate(
                request("x".repeat(17_000), 100),
                new LlmProviderPreflight(1_000, 8_000, new BigDecimal("1.00"), "RUB")
        )).isInstanceOf(LlmProviderPreflightException.class)
                .hasMessageContaining("byte budget");
    }

    private LlmProviderRequest request(String inputValue, int maxOutputTokens) {
        return new LlmProviderRequest(
                UUID.randomUUID(),
                "TEST",
                "test-model",
                "system",
                "{\"value\":\"" + inputValue + "\"}",
                "{}",
                new BigDecimal("0.2"),
                maxOutputTokens,
                Instant.parse("2026-08-03T05:01:30Z")
        );
    }
}
