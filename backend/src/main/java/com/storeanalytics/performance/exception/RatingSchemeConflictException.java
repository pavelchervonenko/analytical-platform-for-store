package com.storeanalytics.performance.exception;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;

public class RatingSchemeConflictException extends BusinessException {

    public RatingSchemeConflictException(String internalMessage) {
        super(BusinessErrorCode.RATING_SCHEME_CONFLICT, internalMessage);
    }
}
