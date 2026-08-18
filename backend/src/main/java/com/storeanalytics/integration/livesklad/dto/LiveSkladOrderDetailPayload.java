package com.storeanalytics.integration.livesklad.dto;

import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

public record LiveSkladOrderDetailPayload(
        String externalId,
        String documentNumber,
        Instant createdAt,
        Instant sourceUpdatedAt,
        Instant closedAt,
        boolean visible,
        String statusExternalId,
        String statusName,
        String storeExternalId,
        List<LiveSkladOrderPositionPayload> positions,
        JsonNode rawPayload
) {
}
