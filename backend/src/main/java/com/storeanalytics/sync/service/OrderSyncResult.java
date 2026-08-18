package com.storeanalytics.sync.service;

import com.storeanalytics.sync.model.SyncRun;
import com.storeanalytics.sync.model.SyncStatus;
import java.util.UUID;

public record OrderSyncResult(
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
        int qualityIssuesOpened,
        int qualityIssuesResolved
) {

    static OrderSyncResult from(SyncRun run, OrderSyncBatchResult batch) {
        return new OrderSyncResult(
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
                batch.qualityIssuesOpened(),
                batch.qualityIssuesResolved()
        );
    }
}
