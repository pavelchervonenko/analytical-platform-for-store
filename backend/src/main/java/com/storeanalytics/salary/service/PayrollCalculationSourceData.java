package com.storeanalytics.salary.service;

import com.storeanalytics.performance.model.EmployeeWorkShift;
import com.storeanalytics.performance.model.StorePerformancePlan;
import com.storeanalytics.salary.model.PayrollScheme;
import com.storeanalytics.salary.repository.PayrollDailySalesAggregate;
import com.storeanalytics.salary.repository.PayrollSaleSourceFact;
import com.storeanalytics.store.model.Store;
import java.util.List;

record PayrollCalculationSourceData(
        Store store,
        StorePerformancePlan plan,
        PayrollScheme scheme,
        List<PayrollDailySalesAggregate> dailySales,
        List<EmployeeWorkShift> shifts,
        List<PayrollSaleSourceFact> saleSourceFacts
) {

    PayrollCalculationSourceData(
            Store store,
            StorePerformancePlan plan,
            PayrollScheme scheme,
            List<PayrollDailySalesAggregate> dailySales,
            List<EmployeeWorkShift> shifts
    ) {
        this(store, plan, scheme, dailySales, shifts, List.of());
    }
}
