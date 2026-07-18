package com.storeanalytics.integration.livesklad.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record LiveSkladStorePayload(
        String externalId,
        String name,
        String address,
        String color,
        JsonNode rawPayload
) {
}
