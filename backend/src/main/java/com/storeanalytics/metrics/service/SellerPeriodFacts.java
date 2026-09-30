package com.storeanalytics.metrics.service;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.metrics.repository.AttachRateAggregate;
import com.storeanalytics.metrics.repository.SellerDocumentAggregate;
import java.math.BigDecimal;
import java.util.List;

/** Extended facts are opt-in; the Overview metrics path need not load documents or attach. */
public record SellerPeriodFacts(
        SellerPeriodMetrics metrics,
        List<SellerDocumentAggregate> documents,
        List<AttachRateAggregate> attachRates,
        String attachFormulaVersion,
        SellerReturnAttributionQuality returnAttribution
) {

    public SellerPeriodFacts {
        requireNonNull(metrics, "metrics");
        documents = List.copyOf(documents);
        attachRates = List.copyOf(attachRates);
        requireNonNull(attachFormulaVersion, "attachFormulaVersion");
        requireNonNull(returnAttribution, "returnAttribution");
        BigDecimal revenue = documents.stream().map(SellerDocumentAggregate::netRevenue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (revenue.compareTo(metrics.totals().netRevenue()) != 0) {
            throw new IllegalStateException("Seller document revenue reconciliation failed");
        }
        if (documents.stream().anyMatch(document ->
                !metrics.cohort().employeeIds().contains(document.employeeId()))) {
            throw new IllegalStateException("Seller document is outside the selected cohort");
        }
    }

    public AttachRateResult projectedAttachRates() {
        return AttachRateService.project(metrics.cohort().storeId(), metrics.period(),
                attachFormulaVersion, attachRates);
    }
}
