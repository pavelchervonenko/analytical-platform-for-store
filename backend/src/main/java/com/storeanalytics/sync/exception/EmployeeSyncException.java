package com.storeanalytics.sync.exception;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;
import java.util.UUID;

public class EmployeeSyncException extends BusinessException {

    private final UUID syncRunId;

    public EmployeeSyncException(UUID syncRunId, Throwable cause) {
        super(BusinessErrorCode.EMPLOYEE_SYNC_FAILED, "Employee synchronization failed", cause);
        this.syncRunId = syncRunId;
    }

    public UUID getSyncRunId() {
        return syncRunId;
    }
}
