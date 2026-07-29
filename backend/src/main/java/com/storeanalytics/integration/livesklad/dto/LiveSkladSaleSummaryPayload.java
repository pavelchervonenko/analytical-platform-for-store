package com.storeanalytics.integration.livesklad.dto;

import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;

public record LiveSkladSaleSummaryPayload(
        String externalId,
        String documentNumber,
        Instant occurredAt,
        String sourceType,
        BigDecimal grossAmount,
        BigDecimal netAmount,
        BigDecimal costAmount,
        JsonNode rawPayload
) {
}
