package com.storeanalytics.sync.service;

import com.storeanalytics.integration.livesklad.dto.LiveSkladCashTransactionPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladReturnDetailPayload;
import com.storeanalytics.integration.livesklad.exception.LiveSkladReturnChangedException;
import java.util.List;
import java.util.Objects;

record LiveSkladReturnSource(
        List<LiveSkladCashTransactionPayload> cashTransactions,
        LiveSkladReturnDetailPayload detail
) {

    LiveSkladReturnSource {
        cashTransactions = List.copyOf(cashTransactions);
        if (cashTransactions.isEmpty()) {
            Objects.requireNonNull(
                    detail,
                    "return source without cash transactions must contain detail"
            );
        } else {
            String documentId =
                    cashTransactions.getFirst().documentExternalId();
            if (cashTransactions.stream().anyMatch(transaction -> !Objects.equals(
                    documentId, transaction.documentExternalId()))) {
                throw new IllegalArgumentException(
                        "return cash transactions must reference one document"
                );
            }
            boolean allDeleted = cashTransactions.stream()
                    .allMatch(LiveSkladCashTransactionPayload::deleted);
            if (!allDeleted && detail == null) {
                throw new LiveSkladReturnChangedException();
            }
            if (detail != null
                    && !Objects.equals(documentId, detail.externalId())) {
                throw new LiveSkladReturnChangedException();
            }
        }
    }

    String externalId() {
        return cashTransactions.isEmpty()
                ? detail.externalId()
                : cashTransactions.getFirst().documentExternalId();
    }

    boolean deleted() {
        return !cashTransactions.isEmpty() && cashTransactions.stream()
                .allMatch(LiveSkladCashTransactionPayload::deleted);
    }
}
