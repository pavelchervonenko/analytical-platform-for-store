package com.storeanalytics.report.service;

import com.storeanalytics.metrics.service.AttachRateService;
import com.storeanalytics.metrics.service.AverageKpiService;
import com.storeanalytics.metrics.service.CategoryKpiService;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import com.storeanalytics.metrics.service.StoreKpiService;
import com.storeanalytics.performance.service.EmployeeRatingQueryService;
import com.storeanalytics.performance.service.StorePlanProgressService;
import com.storeanalytics.quality.service.StorePeriodQualityService;
import java.time.YearMonth;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class MonthlyReportCalculators {

    private final StoreKpiService storeKpiService;
    private final CategoryKpiService categoryKpiService;
    private final AverageKpiService averageKpiService;
    private final AttachRateService attachRateService;
    private final StorePlanProgressService planProgressService;
    private final EmployeeRatingQueryService ratingQueryService;
    private final StorePeriodQualityService periodQualityService;

    MonthlyReportCalculators(
            StoreKpiService storeKpiService,
            CategoryKpiService categoryKpiService,
            AverageKpiService averageKpiService,
            AttachRateService attachRateService,
            StorePlanProgressService planProgressService,
            EmployeeRatingQueryService ratingQueryService,
            StorePeriodQualityService periodQualityService
    ) {
        this.storeKpiService = storeKpiService;
        this.categoryKpiService = categoryKpiService;
        this.averageKpiService = averageKpiService;
        this.attachRateService = attachRateService;
        this.planProgressService = planProgressService;
        this.ratingQueryService = ratingQueryService;
        this.periodQualityService = periodQualityService;
    }

    MonthlyReportParts calculate(UUID storeId, YearMonth month) {
        StoreKpiPeriod period = new StoreKpiPeriod(month.atDay(1), month.atEndOfMonth());
        return new MonthlyReportParts(
                storeKpiService.calculate(storeId, period),
                categoryKpiService.calculate(storeId, period),
                averageKpiService.calculate(storeId, period),
                attachRateService.calculate(storeId, period),
                planProgressService.calculate(storeId, month, month.atEndOfMonth()),
                ratingQueryService.get(storeId, period),
                periodQualityService.inspect(storeId, month, month.atEndOfMonth())
        );
    }
}
