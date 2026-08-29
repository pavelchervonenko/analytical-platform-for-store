package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.interpretation.review.WeeklyReviewEmployeeFactsReader.EmployeePeriod;
import com.storeanalytics.interpretation.review.WeeklyReviewFacts.PeriodFacts;
import com.storeanalytics.interpretation.review.WeeklyReviewRevenueRepository.RevenueComparison;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.PeriodContext;
import com.storeanalytics.metrics.service.AttachRateService;
import com.storeanalytics.metrics.service.CategoryKpiService;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import com.storeanalytics.metrics.service.StoreKpiService;
import com.storeanalytics.store.service.StoreDataStatusService;
import com.storeanalytics.store.service.StoreDataStatusView;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Loads two closed weeks for v2 without plan, forecast or current-week dependencies. */
@Component
public class WeeklyReviewFactsSource {

    private final StoreKpiService storeKpiService;
    private final CategoryKpiService categoryKpiService;
    private final AttachRateService attachRateService;
    private final WeeklyReviewEmployeeFactsReader employeeFactsReader;
    private final StoreDataStatusService dataStatusService;
    private final WeeklyReviewRevenueRepository revenueRepository;
    private final WeeklyReviewPolicyV1 policy = new WeeklyReviewPolicyV1();

    public WeeklyReviewFactsSource(
            StoreKpiService storeKpiService,
            CategoryKpiService categoryKpiService,
            AttachRateService attachRateService,
            WeeklyReviewEmployeeFactsReader employeeFactsReader,
            StoreDataStatusService dataStatusService,
            WeeklyReviewRevenueRepository revenueRepository
    ) {
        this.storeKpiService = storeKpiService;
        this.categoryKpiService = categoryKpiService;
        this.attachRateService = attachRateService;
        this.employeeFactsReader = employeeFactsReader;
        this.dataStatusService = dataStatusService;
        this.revenueRepository = revenueRepository;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WeeklyReviewFacts load(UUID storeId, Instant now, String timezone) {
        UUID validatedStoreId = requireNonNull(storeId, "storeId");
        PeriodContext period = policy.period(
                requireNonNull(now, "now"),
                requireNonNull(timezone, "timezone")
        );
        StoreKpiPeriod currentPeriod = new StoreKpiPeriod(
                period.current().start(), period.current().end()
        );
        StoreKpiPeriod previousPeriod = new StoreKpiPeriod(
                period.previous().start(), period.previous().end()
        );
        StoreDataStatusView status = dataStatusService.get(validatedStoreId);
        RevenueComparison revenue = revenueRepository.read(
                validatedStoreId, currentPeriod, previousPeriod
        );
        return new WeeklyReviewFacts(
                validatedStoreId,
                period,
                status,
                periodFacts(validatedStoreId, currentPeriod, revenue.current()),
                periodFacts(validatedStoreId, previousPeriod, revenue.previous()),
                status.lastCompletedSyncAt()
        );
    }

    private PeriodFacts periodFacts(
            UUID storeId,
            StoreKpiPeriod period,
            WeeklyReviewPolicyV1.RevenuePeriod revenue
    ) {
        EmployeePeriod employees = employeeFactsReader.read(storeId, period);
        return new PeriodFacts(
                storeKpiService.calculate(storeId, period),
                categoryKpiService.calculate(storeId, period),
                attachRateService.calculate(storeId, period),
                employees.employees(),
                employees.salesSamples(),
                employees.unattributedReturnDocumentCount(),
                revenue
        );
    }
}
