package com.storeanalytics.report.exception;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;

public class ActiveReportBackfillJobException extends BusinessException {

    public ActiveReportBackfillJobException(String internalMessage) {
        super(BusinessErrorCode.ACTIVE_REPORT_BACKFILL_JOB_EXISTS, internalMessage);
    }
}
