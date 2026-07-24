package com.storeanalytics.salary.exception;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;

public class PayrollStateConflictException extends BusinessException {

    public PayrollStateConflictException(String internalMessage) {
        super(BusinessErrorCode.PAYROLL_STATE_CONFLICT, internalMessage);
    }
}
