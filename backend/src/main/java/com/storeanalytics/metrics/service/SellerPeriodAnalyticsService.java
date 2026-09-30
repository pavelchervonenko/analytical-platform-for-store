package com.storeanalytics.metrics.service;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.metrics.exception.StoreNotFoundException;
import com.storeanalytics.metrics.repository.EmployeeCategoryKpiAggregate;
import com.storeanalytics.metrics.repository.EmployeeCategoryKpiRepository;
import com.storeanalytics.metrics.repository.EmployeeKpiAggregate;
import com.storeanalytics.metrics.repository.EmployeeKpiRepository;
import com.storeanalytics.metrics.repository.SellerAttachRateRepository;
import com.storeanalytics.metrics.repository.SellerCohortRepository;
import com.storeanalytics.metrics.repository.SellerDocumentRepository;
import com.storeanalytics.metrics.repository.SellerReturnAttributionRepository;
import com.storeanalytics.store.repository.StoreRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Shared internal seller analytics. Does not publish reports, score employees or call an AI. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class SellerPeriodAnalyticsService {

    private final StoreRepository stores;
    private final SellerCohortRepository cohorts;
    private final EmployeeKpiRepository employees;
    private final EmployeeCategoryKpiRepository categories;
    private final SellerDocumentRepository documents;
    private final SellerAttachRateRepository attach;
    private final SellerReturnAttributionRepository returnAttribution;

    public SellerPeriodAnalyticsService(
            StoreRepository stores,
            SellerCohortRepository cohorts,
            EmployeeKpiRepository employees,
            EmployeeCategoryKpiRepository categories,
            SellerDocumentRepository documents,
            SellerAttachRateRepository attach,
            SellerReturnAttributionRepository returnAttribution
    ) {
        this.stores = stores;
        this.cohorts = cohorts;
        this.employees = employees;
        this.categories = categories;
        this.documents = documents;
        this.attach = attach;
        this.returnAttribution = returnAttribution;
    }

    public SellerPeriodMetrics readMetrics(UUID storeId, StoreKpiPeriod period) {
        requireNonNull(period, "period");
        return metrics(selectCohort(storeId), period);
    }

    /** Reads each financial projection once, retaining full rows only for Overview reconciliation. */
    public SellerOverviewMetrics readForOverview(UUID storeId, StoreKpiPeriod period) {
        requireNonNull(period, "period");
        SellerCohortSnapshot cohort = selectCohort(storeId);
        List<EmployeeKpiAggregate> allEmployees = employees.aggregate(storeId, period.start(), period.end());
        List<EmployeeCategoryKpiAggregate> allCategories = categories.aggregate(
                storeId, period.start(), period.end());
        return new SellerOverviewMetrics(
                SellerPeriodMetricCalculator.calculate(cohort, period, allEmployees, allCategories),
                EmployeeKpiService.project(storeId, period, allEmployees),
                EmployeeCategoryKpiService.project(storeId, period, allCategories));
    }

    public SellerPeriodComparisonFacts readComparison(
            UUID storeId, StoreKpiPeriod current, StoreKpiPeriod previous
    ) {
        requireNonNull(current, "current");
        requireNonNull(previous, "previous");
        SellerCohortSnapshot cohort = selectCohort(storeId);
        return new SellerPeriodComparisonFacts(facts(cohort, current), facts(cohort, previous));
    }

    private SellerCohortSnapshot selectCohort(UUID storeId) {
        requireNonNull(storeId, "storeId");
        if (!stores.existsById(storeId)) {
            throw new StoreNotFoundException(storeId);
        }
        return cohorts.read(storeId);
    }

    private SellerPeriodMetrics metrics(SellerCohortSnapshot cohort, StoreKpiPeriod period) {
        if (cohort.employeeIds().isEmpty()) {
            return SellerPeriodMetricCalculator.calculate(cohort, period, List.of(), List.of());
        }
        return SellerPeriodMetricCalculator.calculate(cohort, period,
                employees.aggregate(cohort.storeId(), period.start(), period.end()),
                categories.aggregate(cohort.storeId(), period.start(), period.end()));
    }

    private SellerPeriodFacts facts(SellerCohortSnapshot cohort, StoreKpiPeriod period) {
        return new SellerPeriodFacts(metrics(cohort, period),
                documents.read(cohort, period), attach.read(cohort, period), attach.formulaVersion(),
                returnAttribution.read(cohort.storeId(), period));
    }
}
