package com.storeanalytics.common.exception;

import java.util.Objects;

public abstract class BusinessException extends RuntimeException {

    private final BusinessErrorCode errorCode;

    protected BusinessException(BusinessErrorCode errorCode, String internalMessage) {
        super(internalMessage);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    protected BusinessException(
            BusinessErrorCode errorCode,
            String internalMessage,
            Throwable cause
    ) {
        super(internalMessage, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    public BusinessErrorCode getErrorCode() {
        return errorCode;
    }
}
