package com.storeanalytics.interpretation.snapshot;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.metrics.service.StoreKpiPeriod;
import java.time.DayOfWeek;
import java.util.UUID;

public record WeeklyAnalyticsFactsQuery(
        UUID storeId,
        StoreKpiPeriod period,
        StoreKpiPeriod comparisonPeriod
) {

    public WeeklyAnalyticsFactsQuery {
        requireNonNull(storeId, "storeId");
        requireNonNull(period, "period");
        requireNonNull(comparisonPeriod, "comparisonPeriod");
        requireFullWeek(period, "period");
        requireFullWeek(comparisonPeriod, "comparisonPeriod");
        if (!comparisonPeriod.end().equals(period.start().minusDays(1))) {
            throw new IllegalArgumentException(
                    "comparisonPeriod must be the immediately preceding full week"
            );
        }
    }

    private static void requireFullWeek(StoreKpiPeriod value, String field) {
        if (!value.end().equals(value.start().plusDays(6))
                || value.start().getDayOfWeek() != DayOfWeek.MONDAY
                || value.end().getDayOfWeek() != DayOfWeek.SUNDAY) {
            throw new IllegalArgumentException(field + " must be a Monday-Sunday week");
        }
    }
}
