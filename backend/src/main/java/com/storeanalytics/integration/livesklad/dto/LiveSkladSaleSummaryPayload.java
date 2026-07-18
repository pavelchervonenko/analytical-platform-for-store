package com.storeanalytics.integration.livesklad.dto;

import com.fasterxml.jackson.databind.JsonNode;
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
