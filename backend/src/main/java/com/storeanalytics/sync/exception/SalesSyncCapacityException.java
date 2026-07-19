package com.storeanalytics.sync.exception;

import java.util.UUID;

public class SalesSyncCapacityException extends RuntimeException {

    private final UUID syncRunId;
    private final int recordCount;
    private final int maximumRecordCount;

    public SalesSyncCapacityException(
            UUID syncRunId,
            int recordCount,
            int maximumRecordCount
    ) {
        super("Sales synchronization window contains too many records");
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
