package com.storeanalytics.metrics.exception;

import java.util.UUID;

public class StoreNotFoundException extends RuntimeException {

    private final UUID storeId;

    public StoreNotFoundException(UUID storeId) {
        super("Store was not found: " + storeId);
        this.storeId = storeId;
    }

    public UUID getStoreId() {
        return storeId;
    }
}
