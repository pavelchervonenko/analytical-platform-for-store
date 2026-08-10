package com.storeanalytics.quality.service;

import static com.storeanalytics.common.time.ReportingCutoffPolicy.clampToCompletedDay;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.common.exception.InvalidRequestException;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import com.storeanalytics.metrics.service.StoreKpiResult;
import com.storeanalytics.metrics.service.StoreKpiService;
import com.storeanalytics.performance.service.EmployeeRatingEntry;
import com.storeanalytics.performance.service.EmployeeRatingHistoryStatus;
import com.storeanalytics.performance.service.EmployeeRatingQueryService;
import com.storeanalytics.performance.service.EmployeeRatingResult;
import com.storeanalytics.performance.service.StorePlanProgressService;
import com.storeanalytics.performance.service.StorePlanProgressView;
import com.storeanalytics.quality.model.DataQualitySeverity;
import com.storeanalytics.salary.service.PayrollFreshnessStatus;
import com.storeanalytics.salary.service.PayrollPeriodQualityService;
import com.storeanalytics.salary.service.PayrollPeriodQualitySnapshot;
import com.storeanalytics.salary.service.PayrollReadinessView;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.repository.StoreRepository;
import com.storeanalytics.store.service.StoreDataFreshnessStatus;
import com.storeanalytics.store.service.StoreDataStatusService;
import com.storeanalytics.store.service.StoreDataStatusView;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StorePeriodQualityService {

    private static final Comparator<PeriodQualityIssueView> ISSUE_ORDER = Comparator
            .comparingInt((PeriodQualityIssueView issue) -> severityOrder(issue.severity()))
            .thenComparing(PeriodQualityIssueView::area)
            .thenComparing(PeriodQualityIssueView::code);

    private final StoreRepository storeRepository;
    private final StoreDataStatusService dataStatusService;
    private final StoreKpiService storeKpiService;
    private final StorePlanProgressService planProgressService;
    private final EmployeeRatingQueryService ratingQueryService;
    private final PayrollPeriodQualityService payrollQualityService;
    private final Clock clock;

    public StorePeriodQualityService(
            StoreRepository storeRepository,
            StoreDataStatusService dataStatusService,
            StoreKpiService storeKpiService,
            StorePlanProgressService planProgressService,
            EmployeeRatingQueryService ratingQueryService,
            PayrollPeriodQualityService payrollQualityService,
            Clock clock
    ) {
        this.storeRepository = storeRepository;
        this.dataStatusService = dataStatusService;
        this.storeKpiService = storeKpiService;
        this.planProgressService = planProgressService;
        this.ratingQueryService = ratingQueryService;
        this.payrollQualityService = payrollQualityService;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StorePeriodQualityView inspect(
            UUID storeId,
            YearMonth month,
            LocalDate asOfDate
    ) {
        UUID validatedStoreId = requireNonNull(storeId, "storeId");
        YearMonth validatedMonth = requireNonNull(month, "month");
        LocalDate requestedAsOf = requireAsOf(validatedMonth, asOfDate);
        Store store = storeRepository.findById(validatedStoreId)
                .orElseThrow(() -> new com.storeanalytics.metrics.exception.StoreNotFoundException(
                        validatedStoreId
                ));
        StoreDataStatusView dataStatus = dataStatusService.get(validatedStoreId);
        LocalDate asOf = clampToCompletedDay(
                validatedMonth,
                requestedAsOf,
                dataStatus.expectedThroughDate()
        );
        LocalDate start = validatedMonth.atDay(1);
        LocalDate end = validatedMonth.atEndOfMonth();
        StoreKpiResult storeKpi = storeKpiService.calculate(
                validatedStoreId, new StoreKpiPeriod(start, asOf)
        );
        List<PeriodQualityIssueView> issues = new ArrayList<>();
        PeriodSourceDataQualityView sourceData = sourceData(
                dataStatus, storeKpi, asOf, issues
        );
        PeriodPlanQualityView plan = plan(
                validatedStoreId, validatedMonth, asOf, sourceData, issues
        );
        EmployeeRatingResult ratingResult = ratingQueryService.get(
                validatedStoreId, new StoreKpiPeriod(start, asOf)
        );
        PeriodRatingQualityView rating = rating(
                store, end, sourceData, ratingResult, issues
        );
        PayrollPeriodQualitySnapshot payrollSnapshot = payrollQualityService.inspect(
                validatedStoreId, validatedMonth
        );
        PeriodPayrollQualityView payroll = payroll(
                store,
                end, sourceData, payrollSnapshot, issues
        );
        issues.sort(ISSUE_ORDER);
        List<PeriodQualityIssueView> immutableIssues = List.copyOf(issues);
        List<PeriodQualityAreaView> areas = List.of(
                area(PeriodQualityAreaCode.SOURCE_DATA, immutableIssues),
                area(PeriodQualityAreaCode.STORE_PLAN, immutableIssues),
                area(PeriodQualityAreaCode.EMPLOYEE_RATING, immutableIssues),
                area(PeriodQualityAreaCode.PAYROLL, immutableIssues)
        );
        DataQualityHealthStatus status = health(immutableIssues);
        return new StorePeriodQualityView(
                validatedStoreId,
                start,
                start,
                end,
                asOf,
                status,
                status != DataQualityHealthStatus.ERROR,
                areas,
                sourceData,
                plan,
                rating,
                payroll,
                immutableIssues,
                clock.instant()
        );
    }

    private PeriodSourceDataQualityView sourceData(
            StoreDataStatusView dataStatus,
            StoreKpiResult storeKpi,
            LocalDate asOf,
            List<PeriodQualityIssueView> issues
    ) {
        boolean completeThroughAsOf = dataStatus.dataThroughDate() != null
                && !dataStatus.dataThroughDate().isBefore(asOf);
        switch (dataStatus.status()) {
            case CURRENT -> {
                // No synchronization issue.
            }
            case SYNCING -> issues.add(issue(
                    PeriodQualityAreaCode.SOURCE_DATA,
                    "SOURCE_SYNC_IN_PROGRESS",
                    DataQualitySeverity.INFO,
                    "Data synchronization is in progress",
                    null,
                    PeriodQualityAction.WAIT_FOR_SYNC
            ));
            case NOT_SYNCED -> issues.add(issue(
                    PeriodQualityAreaCode.SOURCE_DATA,
                    "SOURCE_DATA_NOT_SYNCED",
                    DataQualitySeverity.ERROR,
                    "Sales and returns have not been synchronized",
                    null,
                    PeriodQualityAction.RUN_SYNC
            ));
            case STALE -> issues.add(issue(
                    PeriodQualityAreaCode.SOURCE_DATA,
                    "SOURCE_DATA_STALE",
                    DataQualitySeverity.WARNING,
                    "Sales or returns are behind the expected date",
                    null,
                    PeriodQualityAction.RUN_SYNC
            ));
            case ERROR -> issues.add(issue(
                    PeriodQualityAreaCode.SOURCE_DATA,
                    "SOURCE_SYNC_FAILED",
                    DataQualitySeverity.ERROR,
                    "Latest synchronization failed",
                    null,
                    PeriodQualityAction.RUN_SYNC
            ));
            default -> throw new IllegalStateException(
                    "Unsupported data freshness status: " + dataStatus.status()
            );
        }
        if (!completeThroughAsOf
                && dataStatus.status() != StoreDataFreshnessStatus.NOT_SYNCED
                && dataStatus.status() != StoreDataFreshnessStatus.ERROR) {
            issues.add(issue(
                    PeriodQualityAreaCode.SOURCE_DATA,
                    "SOURCE_DATA_INCOMPLETE_THROUGH_AS_OF",
                    DataQualitySeverity.ERROR,
                    "Source data does not cover the requested cutoff date",
                    null,
                    PeriodQualityAction.RUN_SYNC
            ));
        }
        long unmapped = storeKpi.dataQuality().unmappedItemCount();
        if (unmapped > 0) {
            issues.add(issue(
                    PeriodQualityAreaCode.SOURCE_DATA,
                    "SOURCE_PRODUCTS_UNMAPPED",
                    DataQualitySeverity.ERROR,
                    "Some product positions are not classified",
                    unmapped,
                    PeriodQualityAction.CLASSIFY_PRODUCTS
            ));
        }
        long missingCosts = storeKpi.dataQuality().missingCostItemCount();
        if (missingCosts > 0) {
            issues.add(issue(
                    PeriodQualityAreaCode.SOURCE_DATA,
                    "SOURCE_COST_DATA_MISSING",
                    DataQualitySeverity.WARNING,
                    "Some product positions have no cost data",
                    missingCosts,
                    PeriodQualityAction.PROVIDE_COST_DATA
            ));
        }
        long unexpectedZeroCosts = storeKpi.dataQuality().unexpectedZeroCostItemCount();
        if (unexpectedZeroCosts > 0) {
            issues.add(issue(
                    PeriodQualityAreaCode.SOURCE_DATA,
                    "SOURCE_COST_DATA_ZERO_UNEXPECTED",
                    DataQualitySeverity.WARNING,
                    "Some product positions have an unexpected zero cost",
                    unexpectedZeroCosts,
                    PeriodQualityAction.PROVIDE_COST_DATA
            ));
        }
        long openIssues = storeKpi.dataQuality().periodOpenConsistencyIssueCount();
        if (openIssues > 0) {
            issues.add(issue(
                    PeriodQualityAreaCode.SOURCE_DATA,
                    "SOURCE_OPEN_QUALITY_ISSUES",
                    DataQualitySeverity.WARNING,
                    "The store has open data consistency issues",
                    openIssues,
                    PeriodQualityAction.REVIEW_DATA_ISSUES
            ));
        }
        return new PeriodSourceDataQualityView(
                dataStatus.status(),
                dataStatus.dataThroughDate(),
                completeThroughAsOf,
                unmapped == 0,
                storeKpi.dataQuality().completeCostData(),
                storeKpi.dataQuality().includedItemCount(),
                unmapped,
                missingCosts,
                unexpectedZeroCosts,
                openIssues
        );
    }

    private PeriodPlanQualityView plan(
            UUID storeId,
            YearMonth month,
            LocalDate asOf,
            PeriodSourceDataQualityView sourceData,
            List<PeriodQualityIssueView> issues
    ) {
        java.util.Optional<StorePlanProgressView> progress =
                planProgressService.find(storeId, month, asOf);
        if (progress.isPresent()) {
            StorePlanProgressView value = progress.get();
            return new PeriodPlanQualityView(
                    true,
                    value.dataQuality().completeThroughAsOf(),
                    value.dataQuality().classificationComplete(),
                    value.dataQuality().unmappedItemCount(),
                    value.dataQuality().openQualityIssueCount(),
                    value.formulaVersion()
            );
        }
        issues.add(issue(
                PeriodQualityAreaCode.STORE_PLAN,
                "STORE_PLAN_MISSING",
                DataQualitySeverity.ERROR,
                "The store plan is not configured for the requested month",
                null,
                PeriodQualityAction.SET_STORE_PLAN
        ));
        return new PeriodPlanQualityView(
                false,
                sourceData.completeThroughAsOf(),
                sourceData.classificationComplete(),
                sourceData.unmappedItemCount(),
                sourceData.openQualityIssueCount(),
                null
        );
    }

    private PeriodRatingQualityView rating(
            Store store,
            LocalDate periodEnd,
            PeriodSourceDataQualityView sourceData,
            EmployeeRatingResult result,
            List<PeriodQualityIssueView> issues
    ) {
        List<EmployeeRatingEntry> employees = result.employees();
        int eligible = count(employees, EmployeeRatingEntry::ratingEligible);
        int withShifts = count(employees, entry -> entry.ratingEligible()
                && entry.shiftCount() > 0);
        int ranked = count(employees, EmployeeRatingEntry::ranked);
        int salesWithoutShift = count(employees, entry -> entry.netRevenue().signum() != 0
                && (entry.shiftCount() == 0 || entry.workedHours().signum() <= 0));
        BigDecimal minimumCoverage = result.formula().minimumCoveragePercent();
        int insufficientCoverage = count(employees, entry -> entry.ratingEligible()
                && entry.shiftCount() > 0
                && !entry.ranked()
                && entry.scores().coveragePercent().compareTo(minimumCoverage) < 0);
        if (!result.plan().complete()) {
            issues.add(issue(
                    PeriodQualityAreaCode.EMPLOYEE_RATING,
                    "RATING_PLAN_COVERAGE_INCOMPLETE",
                    DataQualitySeverity.ERROR,
                    "The rating period is not fully covered by store plans",
                    null,
                    PeriodQualityAction.SET_STORE_PLAN
            ));
        }
        if (!sourceData.completeThroughAsOf()) {
            issues.add(issue(
                    PeriodQualityAreaCode.EMPLOYEE_RATING,
                    "RATING_INPUT_DATA_INCOMPLETE",
                    DataQualitySeverity.ERROR,
                    "The rating cannot be trusted through the requested cutoff date",
                    null,
                    PeriodQualityAction.RUN_SYNC
            ));
        }
        if (eligible == 0) {
            issues.add(issue(
                    PeriodQualityAreaCode.EMPLOYEE_RATING,
                    "RATING_NO_ELIGIBLE_EMPLOYEES",
                    DataQualitySeverity.ERROR,
                    "No employees are eligible for the rating",
                    0L,
                    PeriodQualityAction.REVIEW_EMPLOYEE_ELIGIBILITY
            ));
        }
        if (eligible > 0 && withShifts == 0) {
            issues.add(issue(
                    PeriodQualityAreaCode.EMPLOYEE_RATING,
                    "RATING_NO_EMPLOYEES_WITH_SHIFTS",
                    DataQualitySeverity.ERROR,
                    "No employee shifts are configured for the rating period",
                    0L,
                    PeriodQualityAction.UPDATE_WORK_SCHEDULE
            ));
        }
        if (salesWithoutShift > 0) {
            issues.add(issue(
                    PeriodQualityAreaCode.EMPLOYEE_RATING,
                    "RATING_SALES_WITHOUT_SHIFT",
                    DataQualitySeverity.ERROR,
                    "Some employees have sales but no worked shift hours",
                    (long) salesWithoutShift,
                    PeriodQualityAction.UPDATE_WORK_SCHEDULE
            ));
        }
        if (withShifts > 0 && ranked == 0) {
            issues.add(issue(
                    PeriodQualityAreaCode.EMPLOYEE_RATING,
                    "RATING_NO_RANKED_EMPLOYEES",
                    DataQualitySeverity.WARNING,
                    "No employees have enough metric coverage to be ranked",
                    0L,
                    PeriodQualityAction.REVIEW_EMPLOYEE_ELIGIBILITY
            ));
        }
        if (insufficientCoverage > 0) {
            issues.add(issue(
                    PeriodQualityAreaCode.EMPLOYEE_RATING,
                    "RATING_SCORE_COVERAGE_INSUFFICIENT",
                    DataQualitySeverity.WARNING,
                    "Some employees do not meet the minimum score coverage",
                    (long) insufficientCoverage,
                    PeriodQualityAction.REVIEW_EMPLOYEE_ELIGIBILITY
            ));
        }
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(store.getTimezone())));
        if (periodEnd.isBefore(today)
                && result.history().status() == EmployeeRatingHistoryStatus.LIVE) {
            issues.add(issue(
                    PeriodQualityAreaCode.EMPLOYEE_RATING,
                    "RATING_HISTORY_NOT_FINALIZED",
                    DataQualitySeverity.WARNING,
                    "The completed rating period has not been finalized",
                    null,
                    PeriodQualityAction.FINALIZE_RATING
            ));
        }
        return new PeriodRatingQualityView(
                result.plan().complete(),
                employees.size(),
                eligible,
                withShifts,
                ranked,
                salesWithoutShift,
                insufficientCoverage,
                result.history().status(),
                result.formula().version()
        );
    }

    private PeriodPayrollQualityView payroll(
            Store store,
            LocalDate periodEnd,
            PeriodSourceDataQualityView sourceData,
            PayrollPeriodQualitySnapshot snapshot,
            List<PeriodQualityIssueView> issues
    ) {
        PayrollReadinessView readiness = snapshot.readiness();
        if (!readiness.planPresent()) {
            issues.add(issue(
                    PeriodQualityAreaCode.PAYROLL,
                    "PAYROLL_PLAN_MISSING",
                    DataQualitySeverity.ERROR,
                    "Payroll cannot be calculated without the store plan",
                    null,
                    PeriodQualityAction.SET_STORE_PLAN
            ));
        }
        if (!readiness.schemePresent()) {
            issues.add(issue(
                    PeriodQualityAreaCode.PAYROLL,
                    "PAYROLL_SCHEME_MISSING",
                    DataQualitySeverity.ERROR,
                    "No payroll formula is effective for the requested month",
                    null,
                    PeriodQualityAction.REVIEW_DATA_ISSUES
            ));
        }
        if (readiness.unmappedItemCount() > 0) {
            issues.add(issue(
                    PeriodQualityAreaCode.PAYROLL,
                    "PAYROLL_PRODUCTS_UNMAPPED",
                    DataQualitySeverity.ERROR,
                    "Payroll contains unclassified product positions",
                    (long) readiness.unmappedItemCount(),
                    PeriodQualityAction.CLASSIFY_PRODUCTS
            ));
        }
        if (readiness.missingCostItemCount() > 0) {
            issues.add(issue(
                    PeriodQualityAreaCode.PAYROLL,
                    "PAYROLL_REQUIRED_COST_MISSING",
                    DataQualitySeverity.ERROR,
                    "Payroll requires missing gross-profit cost data",
                    (long) readiness.missingCostItemCount(),
                    PeriodQualityAction.PROVIDE_COST_DATA
            ));
        }
        if (readiness.daysWithoutShift() > 0) {
            issues.add(issue(
                    PeriodQualityAreaCode.PAYROLL,
                    "PAYROLL_DAYS_WITHOUT_SHIFT",
                    DataQualitySeverity.ERROR,
                    "Some payroll fund days have no employee shifts",
                    (long) readiness.daysWithoutShift(),
                    PeriodQualityAction.UPDATE_WORK_SCHEDULE
            ));
        }
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(store.getTimezone())));
        if (periodEnd.isBefore(today)
                && (sourceData.dataThroughDate() == null
                || sourceData.dataThroughDate().isBefore(periodEnd))) {
            issues.add(issue(
                    PeriodQualityAreaCode.PAYROLL,
                    "PAYROLL_PERIOD_DATA_INCOMPLETE",
                    DataQualitySeverity.ERROR,
                    "Source data does not cover the completed payroll month",
                    null,
                    PeriodQualityAction.RUN_SYNC
            ));
        }
        if (!snapshot.calculated()) {
            issues.add(issue(
                    PeriodQualityAreaCode.PAYROLL,
                    "PAYROLL_NOT_CALCULATED",
                    DataQualitySeverity.INFO,
                    "Payroll has not been calculated for the requested month",
                    null,
                    PeriodQualityAction.CALCULATE_PAYROLL
            ));
        } else if (snapshot.freshness().status() != PayrollFreshnessStatus.CURRENT) {
            issues.add(issue(
                    PeriodQualityAreaCode.PAYROLL,
                    "PAYROLL_RECALCULATION_REQUIRED",
                    DataQualitySeverity.ERROR,
                    "The latest payroll calculation is not current",
                    (long) snapshot.freshness().reasons().size(),
                    PeriodQualityAction.RECALCULATE_PAYROLL
            ));
        }
        return new PeriodPayrollQualityView(
                readiness.status(),
                readiness.canCalculate(),
                readiness.canApprove(),
                readiness.planPresent(),
                readiness.schemePresent(),
                readiness.salesDayCount(),
                readiness.scheduledDayCount(),
                readiness.unmappedItemCount(),
                readiness.missingCostItemCount(),
                readiness.daysWithoutShift(),
                snapshot.calculated(),
                snapshot.runStatus(),
                snapshot.freshness()
        );
    }

    private PeriodQualityAreaView area(
            PeriodQualityAreaCode code,
            List<PeriodQualityIssueView> issues
    ) {
        List<PeriodQualityIssueView> areaIssues = issues.stream()
                .filter(issue -> issue.area() == code)
                .toList();
        int errors = countSeverity(areaIssues, DataQualitySeverity.ERROR);
        int warnings = countSeverity(areaIssues, DataQualitySeverity.WARNING);
        int information = countSeverity(areaIssues, DataQualitySeverity.INFO);
        return new PeriodQualityAreaView(
                code,
                health(errors, warnings),
                errors == 0,
                areaIssues.size(),
                errors,
                warnings,
                information
        );
    }

    private PeriodQualityIssueView issue(
            PeriodQualityAreaCode area,
            String code,
            DataQualitySeverity severity,
            String message,
            Long affectedCount,
            PeriodQualityAction action
    ) {
        return new PeriodQualityIssueView(
                area + ":" + code,
                area,
                code,
                severity,
                message,
                affectedCount,
                action
        );
    }

    private DataQualityHealthStatus health(List<PeriodQualityIssueView> issues) {
        return health(
                countSeverity(issues, DataQualitySeverity.ERROR),
                countSeverity(issues, DataQualitySeverity.WARNING)
        );
    }

    private DataQualityHealthStatus health(int errors, int warnings) {
        if (errors > 0) {
            return DataQualityHealthStatus.ERROR;
        }
        return warnings > 0 ? DataQualityHealthStatus.WARNING : DataQualityHealthStatus.OK;
    }

    private int countSeverity(
            List<PeriodQualityIssueView> issues,
            DataQualitySeverity severity
    ) {
        return (int) issues.stream().filter(issue -> issue.severity() == severity).count();
    }

    private int count(
            List<EmployeeRatingEntry> employees,
            java.util.function.Predicate<EmployeeRatingEntry> predicate
    ) {
        return (int) employees.stream().filter(predicate).count();
    }

    private LocalDate requireAsOf(YearMonth month, LocalDate asOfDate) {
        LocalDate validated = requireNonNull(asOfDate, "asOf");
        if (!YearMonth.from(validated).equals(month)) {
            throw new InvalidRequestException("asOf must be inside the requested month");
        }
        return validated;
    }

    private static int severityOrder(DataQualitySeverity severity) {
        return switch (severity) {
            case ERROR -> 0;
            case WARNING -> 1;
            case INFO -> 2;
        };
    }
}
