package com.storeanalytics.sync.exception;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;
import java.time.LocalDate;

public class SyncClassificationRequiredException extends BusinessException {

    public SyncClassificationRequiredException(LocalDate periodStart) {
        super(
                BusinessErrorCode.SYNC_CLASSIFICATION_REQUIRED,
                "No approved product classification is effective at backfill start "
                        + periodStart
        );
    }
}
