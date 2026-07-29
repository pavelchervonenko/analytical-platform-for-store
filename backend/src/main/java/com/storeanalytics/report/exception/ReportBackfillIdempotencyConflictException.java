package com.storeanalytics.report.exception;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;

public class ReportBackfillIdempotencyConflictException extends BusinessException {

    public ReportBackfillIdempotencyConflictException() {
        super(
                BusinessErrorCode.REPORT_BACKFILL_IDEMPOTENCY_CONFLICT,
                "Idempotency key belongs to another report backfill request"
        );
    }
}
