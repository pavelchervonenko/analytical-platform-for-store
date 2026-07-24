package com.storeanalytics.sync.exception;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;
import java.util.UUID;

public class SyncJobNotFoundException extends BusinessException {

    public SyncJobNotFoundException(UUID jobId) {
        super(BusinessErrorCode.SYNC_JOB_NOT_FOUND, "Synchronization job not found: " + jobId);
    }
}
