package com.storeanalytics.report.exception;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;
import java.util.UUID;

public class ReportNotFoundException extends BusinessException {

    public ReportNotFoundException(UUID reportId) {
        super(BusinessErrorCode.REPORT_NOT_FOUND, "report does not exist: " + reportId);
    }
}
