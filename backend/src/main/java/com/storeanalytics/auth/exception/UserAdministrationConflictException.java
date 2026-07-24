package com.storeanalytics.auth.exception;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;

public class UserAdministrationConflictException extends BusinessException {

    public UserAdministrationConflictException(String internalMessage) {
        super(BusinessErrorCode.USER_ADMINISTRATION_CONFLICT, internalMessage);
    }
}
