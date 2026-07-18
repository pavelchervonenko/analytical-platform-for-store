package com.storeanalytics.integration.livesklad.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record LiveSkladCashRegisterPayload(
        String externalId,
        String name,
        String storeExternalId,
        JsonNode rawPayload
) {
}
