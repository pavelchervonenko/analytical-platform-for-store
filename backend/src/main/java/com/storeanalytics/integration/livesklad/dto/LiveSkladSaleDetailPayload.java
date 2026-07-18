package com.storeanalytics.integration.livesklad.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record LiveSkladSaleDetailPayload(
        String externalId,
        String documentNumber,
        Instant occurredAt,
        Instant sourceUpdatedAt,
        String sourceType,
        String storeExternalId,
        String employeeExternalId,
        String employeeName,
        BigDecimal cashAmount,
        BigDecimal cardAmount,
        BigDecimal bankTransferAmount,
        List<LiveSkladSalePositionPayload> positions,
        JsonNode rawPayload
) {
}
