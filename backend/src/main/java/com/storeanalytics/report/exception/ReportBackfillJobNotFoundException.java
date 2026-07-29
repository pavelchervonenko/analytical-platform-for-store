package com.storeanalytics.report.exception;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;
import java.util.UUID;

public class ReportBackfillJobNotFoundException extends BusinessException {

    public ReportBackfillJobNotFoundException(UUID jobId) {
        super(
                BusinessErrorCode.REPORT_BACKFILL_JOB_NOT_FOUND,
                "Report backfill job does not exist: " + jobId
        );
    }
}
