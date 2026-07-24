package com.storeanalytics.auth.exception;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;

public class UserEmailConflictException extends BusinessException {

    public UserEmailConflictException(String email) {
        super(
                BusinessErrorCode.USER_EMAIL_CONFLICT,
                "Application user already exists for email: " + email
        );
    }
}
