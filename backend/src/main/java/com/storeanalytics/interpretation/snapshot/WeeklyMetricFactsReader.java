package com.storeanalytics.interpretation.snapshot;

import com.storeanalytics.metrics.service.AttachRateService;
import com.storeanalytics.metrics.service.CategoryKpiService;
import com.storeanalytics.metrics.service.EmployeeCategoryKpiService;
import com.storeanalytics.metrics.service.EmployeeKpiService;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import com.storeanalytics.metrics.service.StoreKpiService;
import com.storeanalytics.performance.service.EmployeeRatingService;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class WeeklyMetricFactsReader {

    private final StoreKpiService storeKpiService;
    private final CategoryKpiService categoryKpiService;
    private final AttachRateService attachRateService;
    private final EmployeeKpiService employeeKpiService;
    private final EmployeeCategoryKpiService employeeCategoryKpiService;
    private final EmployeeRatingService employeeRatingService;
    private final WeeklyEmployeeSalesSampleReader employeeSalesSampleReader;

    WeeklyMetricFactsReader(
            StoreKpiService storeKpiService,
            CategoryKpiService categoryKpiService,
            AttachRateService attachRateService,
            EmployeeKpiService employeeKpiService,
            EmployeeCategoryKpiService employeeCategoryKpiService,
            EmployeeRatingService employeeRatingService,
            WeeklyEmployeeSalesSampleReader employeeSalesSampleReader
    ) {
        this.storeKpiService = storeKpiService;
        this.categoryKpiService = categoryKpiService;
        this.attachRateService = attachRateService;
        this.employeeKpiService = employeeKpiService;
        this.employeeCategoryKpiService = employeeCategoryKpiService;
        this.employeeRatingService = employeeRatingService;
        this.employeeSalesSampleReader = employeeSalesSampleReader;
    }

    WeeklyPeriodFacts read(UUID storeId, StoreKpiPeriod period) {
        return new WeeklyPeriodFacts(
                storeKpiService.calculate(storeId, period),
                categoryKpiService.calculate(storeId, period),
                attachRateService.calculate(storeId, period),
                employeeKpiService.calculate(storeId, period),
                employeeCategoryKpiService.calculate(storeId, period),
                employeeRatingService.calculate(storeId, period),
                employeeSalesSampleReader.read(storeId, period)
        );
    }
}
