package com.storeanalytics.auth.exception;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;
import java.util.UUID;

public class ManagedUserNotFoundException extends BusinessException {

    public ManagedUserNotFoundException(UUID userId) {
        super(BusinessErrorCode.USER_NOT_FOUND, "Application user not found: " + userId);
    }
}
