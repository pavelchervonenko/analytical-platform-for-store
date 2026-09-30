package com.storeanalytics.metrics.warranty;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;

public class WarrantyDecisionException extends BusinessException {
    public WarrantyDecisionException(BusinessErrorCode code) {
        super(code, code.name());
    }
}
