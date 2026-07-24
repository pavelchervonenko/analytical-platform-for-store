package com.storeanalytics.auth.exception;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;

public class PasswordPolicyViolationException extends BusinessException {

    public PasswordPolicyViolationException(String internalMessage) {
        super(BusinessErrorCode.PASSWORD_POLICY_VIOLATION, internalMessage);
    }
}
