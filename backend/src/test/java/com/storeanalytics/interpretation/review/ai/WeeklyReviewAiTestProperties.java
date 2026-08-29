package com.storeanalytics.interpretation.review.ai;

import java.math.BigDecimal;
import java.time.Duration;

public final class WeeklyReviewAiTestProperties {

    private WeeklyReviewAiTestProperties() {
    }

    public static WeeklyReviewAiGenerationProperties properties(
            boolean enabled,
            boolean plannerEnabled,
            boolean workerEnabled
    ) {
        return new WeeklyReviewAiGenerationProperties(
                enabled,
                plannerEnabled,
                workerEnabled,
                "YANDEX",
                new BigDecimal("0.1"),
                1400,
                2,
                Duration.ofMinutes(1),
                Duration.ofSeconds(5),
                Duration.ofMinutes(4),
                Duration.ofSeconds(30),
                Duration.ofMinutes(3),
                Duration.ofSeconds(30),
                Duration.ofMinutes(10),
                Duration.ofHours(2),
                Duration.ofMinutes(5),
                10,
                131_072,
                new BigDecimal("10.00"),
                new BigDecimal("100.00")
        );
    }
}
