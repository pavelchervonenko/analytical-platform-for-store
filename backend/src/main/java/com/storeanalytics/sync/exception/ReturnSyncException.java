package com.storeanalytics.sync.exception;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;
import java.util.UUID;

public class ReturnSyncException extends BusinessException {

    private final UUID syncRunId;

    public ReturnSyncException(UUID syncRunId, Throwable cause) {
        super(BusinessErrorCode.RETURN_SYNC_FAILED, "Return synchronization failed", cause);
        this.syncRunId = syncRunId;
    }

    public UUID getSyncRunId() {
        return syncRunId;
    }
}
