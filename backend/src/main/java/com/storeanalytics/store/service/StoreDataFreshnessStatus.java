package com.storeanalytics.store.service;

public enum StoreDataFreshnessStatus {
    NOT_SYNCED,
    CURRENT,
    STALE,
    SYNCING,
    ERROR
}
