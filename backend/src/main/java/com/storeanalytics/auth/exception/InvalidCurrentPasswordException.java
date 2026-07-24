package com.storeanalytics.auth.exception;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;

public class InvalidCurrentPasswordException extends BusinessException {

    public InvalidCurrentPasswordException() {
        super(BusinessErrorCode.INVALID_CURRENT_PASSWORD, "Current password is invalid");
    }
}
