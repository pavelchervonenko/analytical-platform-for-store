package com.storeanalytics.sync.service;

import com.storeanalytics.sync.model.SyncRun;
import com.storeanalytics.sync.model.SyncStatus;
import java.util.UUID;

public record StoreSyncResult(
        UUID syncRunId,
        SyncStatus status,
        int recordsFetched,
        int recordsCreated,
        int recordsUpdated,
        int recordsSkipped,
        int recordsFailed
) {

    public static StoreSyncResult from(SyncRun run) {
        return new StoreSyncResult(
                run.getId(),
                run.getStatus(),
                run.getRecordsFetched(),
                run.getRecordsCreated(),
                run.getRecordsUpdated(),
                run.getRecordsSkipped(),
                run.getRecordsFailed()
        );
    }
}
