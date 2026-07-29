package com.storeanalytics.integration.livesklad.dto;

import tools.jackson.databind.JsonNode;

public record LiveSkladEmployeePayload(
        String externalId,
        String fullName,
        JsonNode rawPayload
) {
}
