package com.storeanalytics.performance.exception;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;

public class RatingSchemeNotFoundException extends BusinessException {

    public RatingSchemeNotFoundException(String internalMessage) {
        super(BusinessErrorCode.RATING_SCHEME_NOT_FOUND, internalMessage);
    }
}
