package com.storeanalytics.metrics.service;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.metrics.repository.SellerDocumentAggregate;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Internal financial-only result. Not a publishable weekly report or an attach projection. */
public record SellerHistoricalFinancialComparison(
        FinancialPeriod current,
        FinancialPeriod previous,
        Set<UUID> currentActionEmployeeIds
) {
    public static final String BASIS = "HISTORICAL_DOCUMENT_MEMBERSHIP_V1";

    public SellerHistoricalFinancialComparison {
        requireNonNull(current, "current");
        requireNonNull(previous, "previous");
        currentActionEmployeeIds = Set.copyOf(requireNonNull(currentActionEmployeeIds, "currentActionEmployeeIds"));
        if (!current.metrics().cohort().equals(previous.metrics().cohort())
                || !current.metrics().cohort().employeeIds().containsAll(currentActionEmployeeIds)) {
            throw new IllegalArgumentException("Historical comparison requires one historical cohort");
        }
    }

    public record FinancialPeriod(SellerPeriodMetrics metrics, List<SellerDocumentAggregate> documents) {
        public FinancialPeriod {
            requireNonNull(metrics, "metrics");
            documents = List.copyOf(requireNonNull(documents, "documents"));
            BigDecimal revenue = documents.stream().map(SellerDocumentAggregate::netRevenue)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (revenue.compareTo(metrics.totals().netRevenue()) != 0
                    || documents.stream().anyMatch(row -> !metrics.cohort().employeeIds().contains(row.employeeId()))) {
                throw new IllegalStateException("Historical document revenue reconciliation failed");
            }
        }
    }
}
