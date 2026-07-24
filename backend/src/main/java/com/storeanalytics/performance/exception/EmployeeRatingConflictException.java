package com.storeanalytics.performance.exception;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;

public class EmployeeRatingConflictException extends BusinessException {

    public EmployeeRatingConflictException(String internalMessage) {
        super(BusinessErrorCode.EMPLOYEE_RATING_CONFLICT, internalMessage);
    }
}
