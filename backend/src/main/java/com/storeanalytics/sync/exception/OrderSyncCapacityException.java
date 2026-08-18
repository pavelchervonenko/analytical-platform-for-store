package com.storeanalytics.sync.exception;

import java.util.UUID;

public class OrderSyncCapacityException extends RuntimeException {

    private final UUID syncRunId;
    private final int recordCount;
    private final int maximumRecordCount;

    public OrderSyncCapacityException(
            UUID syncRunId,
            int recordCount,
            int maximumRecordCount
    ) {
        super("Order synchronization window contains too many records");
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
