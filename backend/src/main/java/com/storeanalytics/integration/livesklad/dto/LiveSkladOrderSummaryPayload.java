package com.storeanalytics.integration.livesklad.dto;

import tools.jackson.databind.JsonNode;
import java.time.Instant;

public record LiveSkladOrderSummaryPayload(
        String externalId,
        String documentNumber,
        Instant createdAt,
        boolean visible,
        String statusExternalId,
        String statusName,
        String storeExternalId,
        JsonNode rawPayload
) {
}
