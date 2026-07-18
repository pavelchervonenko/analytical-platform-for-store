package com.storeanalytics.integration.livesklad.dto;

import java.math.BigDecimal;

public record LiveSkladReturnPositionPayload(
        String externalId,
        String originalSalePositionExternalId,
        String productExternalId,
        String code,
        String sku,
        String name,
        boolean work,
        BigDecimal quantity,
        BigDecimal unitListPrice,
        BigDecimal unitSoldPrice,
        BigDecimal costAmount
) {
}
