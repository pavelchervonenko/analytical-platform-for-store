package com.storeanalytics.sync.exception;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;

public class ActiveSyncJobException extends BusinessException {

    public ActiveSyncJobException() {
        super(BusinessErrorCode.ACTIVE_SYNC_JOB_EXISTS, "An active sync job exists");
    }
}
