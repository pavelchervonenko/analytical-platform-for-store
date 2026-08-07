package com.storeanalytics.notification.daily;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record DailyStorePulsePayload(
        int schemaVersion,
        LocalDate businessDate,
        LocalDate comparisonDate,
        Metric result,
        Metric averageReceipt,
        Metric additionalRevenue,
        Metric additionalRevenuePerPhone,
        List<NamedMetric> categories,
        List<NamedMetric> employees,
        Quality quality
) {
    public DailyStorePulsePayload {
        categories = List.copyOf(categories);
        employees = List.copyOf(employees);
    }

    public record Metric(BigDecimal value, BigDecimal changePercent) {
    }

    public record NamedMetric(
            String code,
            String name,
            BigDecimal value,
            BigDecimal changePercent
    ) {
    }

    public record Quality(boolean completeCostData, long openIssueCount) {
    }
}
