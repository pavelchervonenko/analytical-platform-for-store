package com.storeanalytics.integration.livesklad.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record LiveSkladEmployeePayload(
        String externalId,
        String fullName,
        JsonNode rawPayload
) {
}
