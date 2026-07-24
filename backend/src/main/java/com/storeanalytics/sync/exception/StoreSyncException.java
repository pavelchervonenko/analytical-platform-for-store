package com.storeanalytics.sync.exception;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;
import java.util.UUID;

public class StoreSyncException extends BusinessException {

    private final UUID syncRunId;

    public StoreSyncException(UUID syncRunId, Throwable cause) {
        super(BusinessErrorCode.STORE_SYNC_FAILED, "Store synchronization failed", cause);
        this.syncRunId = syncRunId;
    }

    public UUID getSyncRunId() {
        return syncRunId;
    }
}
