package com.storeanalytics.metrics.exception;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;
import java.util.UUID;

public class StoreNotFoundException extends BusinessException {

    private final UUID storeId;

    public StoreNotFoundException(UUID storeId) {
        super(BusinessErrorCode.STORE_NOT_FOUND, "Store was not found: " + storeId);
        this.storeId = storeId;
    }

    public UUID getStoreId() {
        return storeId;
    }
}
