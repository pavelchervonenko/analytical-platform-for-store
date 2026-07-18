package com.storeanalytics.integration.livesklad.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record LiveSkladReturnDetailPayload(
        String externalId,
        String documentNumber,
        Instant occurredAt,
        Instant sourceUpdatedAt,
        String sourceType,
        String storeExternalId,
        String processingEmployeeExternalId,
        String originalSaleExternalId,
        BigDecimal cashAmount,
        BigDecimal cardAmount,
        BigDecimal bankTransferAmount,
        List<LiveSkladReturnPositionPayload> positions,
        JsonNode rawPayload
) {
}
