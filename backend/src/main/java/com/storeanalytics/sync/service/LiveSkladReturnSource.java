package com.storeanalytics.sync.service;

import com.storeanalytics.integration.livesklad.dto.LiveSkladCashTransactionPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladReturnDetailPayload;
import java.util.List;
import java.util.Objects;

record LiveSkladReturnSource(
        List<LiveSkladCashTransactionPayload> cashTransactions,
        LiveSkladReturnDetailPayload detail
) {

    LiveSkladReturnSource {
        cashTransactions = List.copyOf(cashTransactions);
        if (cashTransactions.isEmpty()) {
            throw new IllegalArgumentException(
                    "return source must contain at least one cash transaction"
            );
        }
        String documentId = cashTransactions.getFirst().documentExternalId();
        if (cashTransactions.stream().anyMatch(transaction ->
                !Objects.equals(documentId, transaction.documentExternalId()))) {
            throw new IllegalArgumentException(
                    "return cash transactions must reference one document"
            );
        }
        boolean allDeleted = cashTransactions.stream()
                .allMatch(LiveSkladCashTransactionPayload::deleted);
        if (!allDeleted && detail == null) {
            throw new IllegalArgumentException(
                    "active return source must contain document detail"
            );
        }
        if (detail != null && !Objects.equals(documentId, detail.externalId())) {
            throw new IllegalArgumentException(
                    "return cash transaction and detail IDs must match"
            );
        }
    }

    String externalId() {
        return cashTransactions.getFirst().documentExternalId();
    }

    boolean deleted() {
        return cashTransactions.stream()
                .allMatch(LiveSkladCashTransactionPayload::deleted);
    }
}
