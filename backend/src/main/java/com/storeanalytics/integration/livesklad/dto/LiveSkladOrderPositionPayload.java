package com.storeanalytics.integration.livesklad.dto;

import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;

public record LiveSkladOrderPositionPayload(
        String externalId,
        String productExternalId,
        String code,
        String sku,
        String name,
        boolean work,
        BigDecimal quantity,
        BigDecimal unitListPrice,
        BigDecimal unitSoldPrice,
        BigDecimal costAmount,
        Instant occurredAt,
        String employeeExternalId,
        String employeeName,
        JsonNode rawPayload
) {
}
