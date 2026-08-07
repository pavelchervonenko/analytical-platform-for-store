package com.storeanalytics.metrics.service;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.storeanalytics.product.model.AttachDenominatorCode;
import java.math.BigDecimal;

public record AttachRateEntry(
        String metricCode,
        String numeratorCategoryCode,
        AttachDenominatorCode denominatorCode,
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
