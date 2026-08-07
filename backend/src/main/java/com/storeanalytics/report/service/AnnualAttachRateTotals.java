package com.storeanalytics.report.service;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

public record AnnualAttachRateTotals(
        String formulaVersion,
        String metricCode,
        @JsonAlias("numeratorQuantity") BigDecimal numeratorReceiptCount,
        @JsonAlias("denominatorQuantity") BigDecimal denominatorReceiptCount,
        BigDecimal ratePerHundred
) {

    /**
     * Transitional JSON alias for consumers of attach-rate-v1.
     */
    @JsonProperty("numeratorQuantity")
    public BigDecimal numeratorQuantity() {
        return numeratorReceiptCount;
    }

    /**
     * Transitional JSON alias for consumers of attach-rate-v1.
     */
    @JsonProperty("denominatorQuantity")
    public BigDecimal denominatorQuantity() {
        return denominatorReceiptCount;
    }
}
