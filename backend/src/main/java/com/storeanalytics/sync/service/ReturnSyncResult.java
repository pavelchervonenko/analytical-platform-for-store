package com.storeanalytics.sync.service;

import com.storeanalytics.sync.model.SyncRun;
import com.storeanalytics.sync.model.SyncStatus;
import java.util.UUID;

public record ReturnSyncResult(
        UUID syncRunId,
        SyncStatus status,
        int recordsFetched,
        int recordsCreated,
        int recordsUpdated,
        int recordsSkipped,
        int recordsFailed,
        int registersCreated,
        int registersUpdated,
        int registersDeactivated,
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
        int qualityIssuesResolved,
        int unresolvedDocuments
) {

    static ReturnSyncResult from(SyncRun run, ReturnSyncBatchResult batch) {
        return new ReturnSyncResult(
                run.getId(),
                run.getStatus(),
                run.getRecordsFetched(),
                run.getRecordsCreated(),
                run.getRecordsUpdated(),
                run.getRecordsSkipped(),
                run.getRecordsFailed(),
                batch.registersCreated(),
                batch.registersUpdated(),
                batch.registersDeactivated(),
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
                batch.qualityIssuesResolved(),
                batch.unresolvedDocuments()
        );
    }
}
