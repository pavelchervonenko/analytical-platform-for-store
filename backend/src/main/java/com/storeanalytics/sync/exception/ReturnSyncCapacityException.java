package com.storeanalytics.sync.exception;

import java.util.UUID;

public class ReturnSyncCapacityException extends RuntimeException {

    private final UUID syncRunId;
    private final int recordCount;
    private final int maximumRecordCount;

    public ReturnSyncCapacityException(
            UUID syncRunId,
            int recordCount,
            int maximumRecordCount
    ) {
        super("Return synchronization window contains too many records");
        this.syncRunId = syncRunId;
        this.recordCount = recordCount;
        this.maximumRecordCount = maximumRecordCount;
    }

    public UUID getSyncRunId() {
        return syncRunId;
    }

    public int getRecordCount() {
        return recordCount;
    }

    public int getMaximumRecordCount() {
        return maximumRecordCount;
    }
}
