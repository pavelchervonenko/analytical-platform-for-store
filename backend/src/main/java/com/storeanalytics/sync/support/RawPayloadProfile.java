package com.storeanalytics.sync.support;

/**
 * Closed set of source payload shapes that may be retained as synchronization evidence.
 */
public enum RawPayloadProfile {
    STORE,
    EMPLOYEE,
    SALE_DOCUMENT,
    CASH_ITEM_DICTIONARY,
    CASH_REGISTER,
    RETURN_DOCUMENT
}
