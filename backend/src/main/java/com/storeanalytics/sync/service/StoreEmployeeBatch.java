package com.storeanalytics.sync.service;

import com.storeanalytics.integration.livesklad.dto.LiveSkladEmployeePayload;
import com.storeanalytics.store.model.Store;
import java.util.List;

record StoreEmployeeBatch(
        Store store,
        List<LiveSkladEmployeePayload> employees
) {
}
