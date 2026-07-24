package com.storeanalytics.salary.exception;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;

public class PayrollSchemeConflictException extends BusinessException {

    public PayrollSchemeConflictException(String internalMessage) {
        super(BusinessErrorCode.PAYROLL_SCHEME_CONFLICT, internalMessage);
    }
}
