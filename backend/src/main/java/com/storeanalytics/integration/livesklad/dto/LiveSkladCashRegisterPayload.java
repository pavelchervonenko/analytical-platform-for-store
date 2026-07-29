package com.storeanalytics.integration.livesklad.dto;

import tools.jackson.databind.JsonNode;

public record LiveSkladCashRegisterPayload(
        String externalId,
        String name,
        String storeExternalId,
        JsonNode rawPayload
) {
}
