package com.storeanalytics.sync.service;

import com.storeanalytics.store.model.Store;
import java.util.List;
import java.util.Objects;

record StoreOrderBatch(Store store, List<LiveSkladOrderSource> orders) {

    StoreOrderBatch {
        Objects.requireNonNull(store, "store must not be null");
        orders = List.copyOf(orders);
    }
}
