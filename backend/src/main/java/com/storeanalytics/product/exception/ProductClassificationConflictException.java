package com.storeanalytics.product.exception;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;

public class ProductClassificationConflictException extends BusinessException {

    public ProductClassificationConflictException(String internalMessage) {
        super(BusinessErrorCode.PRODUCT_CLASSIFICATION_CONFLICT, internalMessage);
    }
}
