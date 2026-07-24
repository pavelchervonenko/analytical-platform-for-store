package com.storeanalytics.sync.exception;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;
import java.util.UUID;

public class SalesSyncException extends BusinessException {

    private final UUID syncRunId;

    public SalesSyncException(UUID syncRunId, Throwable cause) {
        super(BusinessErrorCode.SALES_SYNC_FAILED, "Sales synchronization failed", cause);
        this.syncRunId = syncRunId;
    }

    public UUID getSyncRunId() {
        return syncRunId;
    }
}
