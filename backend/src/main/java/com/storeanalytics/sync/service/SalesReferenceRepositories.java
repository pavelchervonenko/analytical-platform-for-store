package com.storeanalytics.sync.service;

import com.storeanalytics.employee.repository.EmployeeRepository;
import com.storeanalytics.product.repository.AnalyticsCategoryRepository;
import com.storeanalytics.product.repository.ProductCategoryAssignmentRepository;
import com.storeanalytics.store.repository.StoreRepository;
import org.springframework.stereotype.Component;

@Component
record SalesReferenceRepositories(
        StoreRepository stores,
        EmployeeRepository employees,
        AnalyticsCategoryRepository categories,
        ProductCategoryAssignmentRepository assignments
) {
}
