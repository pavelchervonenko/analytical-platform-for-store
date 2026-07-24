package com.storeanalytics.common.exception;

public class InvalidRequestException extends BusinessException {

    public InvalidRequestException(String internalMessage) {
        super(BusinessErrorCode.INVALID_ARGUMENT, internalMessage);
    }

    public InvalidRequestException(String internalMessage, Throwable cause) {
        super(BusinessErrorCode.INVALID_ARGUMENT, internalMessage, cause);
    }
}
