package com.storeanalytics.integration.livesklad.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;

public record LiveSkladCashTransactionPayload(
        String externalId,
        Instant occurredAt,
        Instant sourceUpdatedAt,
        String sourceType,
        String storeExternalId,
        String cashRegisterExternalId,
        String cashItemExternalId,
        String cashItemType,
        boolean cashItemIncome,
        boolean cashItemBalance,
        boolean bankTransfer,
        BigDecimal amount,
        String employeeExternalId,
        String workerExternalId,
        String documentExternalId,
        JsonNode rawPayload
) {

    public boolean deleted() {
        return "delete".equalsIgnoreCase(sourceType);
    }
}
