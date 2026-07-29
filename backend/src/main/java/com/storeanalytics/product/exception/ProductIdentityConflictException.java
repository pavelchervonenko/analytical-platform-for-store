package com.storeanalytics.product.exception;

public class ProductIdentityConflictException extends IllegalStateException {

    public ProductIdentityConflictException(String message) {
        super(message);
    }
}
