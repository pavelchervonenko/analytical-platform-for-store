package com.storeanalytics.auth.exception;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;
import java.time.Duration;

public class LoginThrottledException extends BusinessException {

    private final Duration retryAfter;

    public LoginThrottledException(Duration retryAfter) {
        super(BusinessErrorCode.LOGIN_THROTTLED, "Too many login attempts");
        this.retryAfter = retryAfter;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }
}
