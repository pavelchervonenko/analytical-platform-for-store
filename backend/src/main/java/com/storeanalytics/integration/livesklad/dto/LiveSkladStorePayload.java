package com.storeanalytics.integration.livesklad.dto;

import tools.jackson.databind.JsonNode;

public record LiveSkladStorePayload(
        String externalId,
        String name,
        String address,
        String color,
        JsonNode rawPayload
) {
}
