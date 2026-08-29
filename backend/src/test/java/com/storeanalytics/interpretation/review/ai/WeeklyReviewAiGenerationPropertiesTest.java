package com.storeanalytics.interpretation.review.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class WeeklyReviewAiGenerationPropertiesTest {

    @Test
    void calculatesBoundedRetryBackoff() {
        WeeklyReviewAiGenerationProperties properties =
                WeeklyReviewAiTestProperties.properties(true, false, true);

        assertThat(properties.retryDelay(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.retryDelay(2)).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.retryDelay(20)).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void rejectsUnsafeTimeoutAndBudgetCombinations() {
        assertThatThrownBy(() -> properties(
                Duration.ofMinutes(3),
                Duration.ofMinutes(4),
                new BigDecimal("10"),
                new BigDecimal("100")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provider timeout");
        assertThatThrownBy(() -> properties(
                Duration.ofMinutes(4),
                Duration.ofMinutes(3),
                new BigDecimal("10"),
                new BigDecimal("5")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("daily budget");
    }

    private WeeklyReviewAiGenerationProperties properties(
            Duration lease,
            Duration providerTimeout,
            BigDecimal callBudget,
            BigDecimal dailyBudget
    ) {
        return new WeeklyReviewAiGenerationProperties(
                true, false, true, "YANDEX", new BigDecimal("0.1"), 1400, 2,
                Duration.ofMinutes(1), Duration.ofSeconds(5), lease,
                Duration.ofSeconds(30), providerTimeout, Duration.ofSeconds(30),
                Duration.ofMinutes(10), Duration.ofHours(2),
                Duration.ofMinutes(5), 10, 131_072, callBudget, dailyBudget
        );
    }
}
