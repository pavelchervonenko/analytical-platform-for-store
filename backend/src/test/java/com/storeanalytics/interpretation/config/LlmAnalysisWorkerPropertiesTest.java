package com.storeanalytics.interpretation.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class LlmAnalysisWorkerPropertiesTest {

    @Test
    void rejectsUnsafeLeaseTimeoutAndBudgetValues() {
        assertThatThrownBy(() -> properties(
                Duration.ofSeconds(15), Duration.ofSeconds(15),
                Duration.ofSeconds(90), 524_288, new BigDecimal("50")
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(
                Duration.ofSeconds(10), Duration.ofSeconds(5),
                Duration.ofMinutes(11), 524_288, new BigDecimal("50")
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(
                Duration.ofSeconds(10), Duration.ofSeconds(5),
                Duration.ofSeconds(90), 1_000, new BigDecimal("50")
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(
                Duration.ofSeconds(10), Duration.ofSeconds(5),
                Duration.ofSeconds(90), 524_288, BigDecimal.ZERO
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private LlmAnalysisWorkerProperties properties(
            Duration lease,
            Duration heartbeat,
            Duration timeout,
            int maxRequestBytes,
            BigDecimal maxCost
    ) {
        return new LlmAnalysisWorkerProperties(
                true,
                Duration.ofSeconds(5),
                lease,
                heartbeat,
                Duration.ofSeconds(30),
                timeout,
                maxRequestBytes,
                maxCost
        );
    }
}
