package com.storeanalytics.sync.service;

import com.storeanalytics.product.repository.AnalyticsCategoryRepository;
import com.storeanalytics.product.service.ProductClassificationResolver;
import com.storeanalytics.store.repository.CashRegisterRepository;
import com.storeanalytics.store.repository.StoreRepository;
import org.springframework.stereotype.Component;

@Component
record ReturnReferenceRepositories(
        StoreRepository stores,
        CashRegisterRepository cashRegisters,
        AnalyticsCategoryRepository categories,
        ProductClassificationResolver classificationResolver
) {
}
