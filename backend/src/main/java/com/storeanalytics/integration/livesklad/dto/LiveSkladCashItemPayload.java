package com.storeanalytics.integration.livesklad.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record LiveSkladCashItemPayload(
        String externalId,
        String name,
        String sourceType,
        boolean income,
        boolean balance,
        JsonNode rawPayload
) {
}
