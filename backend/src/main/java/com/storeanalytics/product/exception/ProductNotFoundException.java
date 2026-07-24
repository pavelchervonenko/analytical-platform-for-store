package com.storeanalytics.product.exception;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;
import java.util.UUID;

public class ProductNotFoundException extends BusinessException {

    public ProductNotFoundException(UUID productId) {
        super(BusinessErrorCode.PRODUCT_NOT_FOUND, "Product was not found: " + productId);
    }
}
