package com.storeanalytics.sync.service;

import com.storeanalytics.integration.livesklad.dto.LiveSkladCashRegisterPayload;
import com.storeanalytics.store.model.Store;
import java.util.List;
import java.util.Objects;

record StoreReturnBatch(
        Store store,
        List<LiveSkladCashRegisterPayload> cashRegisters,
        List<LiveSkladReturnSource> returns
) {

    StoreReturnBatch {
        Objects.requireNonNull(store, "store must not be null");
        cashRegisters = List.copyOf(cashRegisters);
        returns = List.copyOf(returns);
    }
}
