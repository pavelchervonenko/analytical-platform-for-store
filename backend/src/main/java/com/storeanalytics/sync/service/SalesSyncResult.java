package com.storeanalytics.sync.service;

import com.storeanalytics.sync.model.SyncRun;
import com.storeanalytics.sync.model.SyncStatus;
import java.util.UUID;

public record SalesSyncResult(
        UUID syncRunId,
        SyncStatus status,
        int recordsFetched,
        int recordsCreated,
        int recordsUpdated,
        int recordsSkipped,
        int recordsFailed,
        int documentsDeleted,
        int productsCreated,
        int productsUpdated,
        int itemsCreated,
        int itemsUpdated,
        int itemsDeleted,
        int paymentsCreated,
        int paymentsUpdated,
        int paymentsDeleted,
        int qualityIssuesOpened,
        int qualityIssuesResolved
) {

    static SalesSyncResult from(SyncRun run, SalesSyncBatchResult batch) {
        return new SalesSyncResult(
                run.getId(),
                run.getStatus(),
                run.getRecordsFetched(),
                run.getRecordsCreated(),
                run.getRecordsUpdated(),
                run.getRecordsSkipped(),
                run.getRecordsFailed(),
                batch.documentsDeleted(),
                batch.productsCreated(),
                batch.productsUpdated(),
                batch.itemsCreated(),
                batch.itemsUpdated(),
                batch.itemsDeleted(),
                batch.paymentsCreated(),
                batch.paymentsUpdated(),
                batch.paymentsDeleted(),
                batch.qualityIssuesOpened(),
                batch.qualityIssuesResolved()
        );
    }
}
