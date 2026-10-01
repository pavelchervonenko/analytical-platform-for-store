package com.storeanalytics.metrics.warranty;

import com.storeanalytics.common.exception.BusinessException;
import com.storeanalytics.common.exception.BusinessErrorCode;

public class WarrantyCaseNotFoundException extends BusinessException {
    public WarrantyCaseNotFoundException() {
        super(BusinessErrorCode.WARRANTY_CASE_NOT_FOUND,
                "Warranty source is not available in this store");
    }
}
