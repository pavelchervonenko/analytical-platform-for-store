package com.storeanalytics.sync.service;

import com.storeanalytics.store.model.Store;
import java.util.List;
import java.util.Objects;

record StoreSalesBatch(Store store, List<LiveSkladSaleSource> sales) {

    StoreSalesBatch {
        Objects.requireNonNull(store, "store must not be null");
        sales = List.copyOf(sales);
    }
}
