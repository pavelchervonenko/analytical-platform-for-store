package com.storeanalytics.performance.service;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record StorePlanProgressView(
        UUID storeId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate periodStart,
        @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate periodEnd,
        @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate asOfDate,
        int totalDays,
        int elapsedDays,
        int remainingDays,
        String formulaVersion,
        StorePerformancePlanView plan,
        StorePlanProgressDataQuality dataQuality,
        int achievedDirectionCount,
        boolean allDirectionsAchieved,
        List<StorePlanDirectionCode> focusDirections,
        List<StorePlanDirectionView> directions,
        List<StorePlanDailyTargetView> dailyTargets,
        Instant calculatedAt
) {
}
