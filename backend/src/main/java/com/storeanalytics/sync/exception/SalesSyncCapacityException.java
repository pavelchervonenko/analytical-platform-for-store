package com.storeanalytics.sync.exception;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;
import java.util.UUID;

public class SalesSyncCapacityException extends BusinessException {

    private final UUID syncRunId;
    private final int recordCount;
    private final int maximumRecordCount;

    public SalesSyncCapacityException(
            UUID syncRunId,
            int recordCount,
            int maximumRecordCount
    ) {
        super(
                BusinessErrorCode.SALES_SYNC_WINDOW_TOO_LARGE,
                "Sales synchronization window contains too many records"
        );
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
