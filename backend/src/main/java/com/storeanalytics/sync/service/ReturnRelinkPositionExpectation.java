package com.storeanalytics.sync.service;

import java.math.BigDecimal;

public record ReturnRelinkPositionExpectation(
        String returnPositionExternalId,
        String originalSalePositionExternalId,
        String productExternalId,
        BigDecimal quantity,
        BigDecimal netAmount,
        BigDecimal costAmount
) {
}
