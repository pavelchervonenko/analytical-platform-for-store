package com.storeanalytics.quality.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeanalytics.metrics.service.StoreKpiDataQuality;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import com.storeanalytics.metrics.service.StoreKpiResult;
import com.storeanalytics.metrics.service.StoreKpiService;
import com.storeanalytics.performance.service.EmployeeRatingEntry;
import com.storeanalytics.performance.service.EmployeeRatingHistoryView;
import com.storeanalytics.performance.service.EmployeeRatingQueryService;
import com.storeanalytics.performance.service.EmployeeRatingResult;
import com.storeanalytics.performance.service.RatingFormulaView;
import com.storeanalytics.performance.service.RatingPlanContext;
import com.storeanalytics.performance.service.RatingScoreBreakdown;
import com.storeanalytics.performance.service.StorePlanProgressDataQuality;
import com.storeanalytics.performance.service.StorePlanProgressService;
import com.storeanalytics.performance.service.StorePlanProgressView;
import com.storeanalytics.salary.model.PayrollRunStatus;
import com.storeanalytics.salary.service.PayrollFreshnessStatus;
import com.storeanalytics.salary.service.PayrollFreshnessView;
import com.storeanalytics.salary.service.PayrollPeriodQualityService;
import com.storeanalytics.salary.service.PayrollPeriodQualitySnapshot;
import com.storeanalytics.salary.service.PayrollReadinessStatus;
import com.storeanalytics.salary.service.PayrollReadinessView;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.repository.StoreRepository;
import com.storeanalytics.store.service.StoreDataFreshnessStatus;
import com.storeanalytics.store.service.StoreDataStatusService;
import com.storeanalytics.store.service.StoreDataStatusView;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StorePeriodQualityServiceTest {

    private static final UUID STORE_ID = UUID.randomUUID();
    private static final YearMonth MONTH = YearMonth.of(2026, 7);
    private static final LocalDate AS_OF = LocalDate.of(2026, 7, 20);
    private static final Instant NOW = Instant.parse("2026-07-20T12:00:00Z");

    @Test
    void returnsReadyWhenEveryBusinessInputIsComplete() {
        Fixture fixture = fixture();

        StorePeriodQualityView result = fixture.service().inspect(STORE_ID, MONTH, AS_OF);

        assertThat(result.status()).isEqualTo(DataQualityHealthStatus.OK);
        assertThat(result.readyForDecisions()).isTrue();
        assertThat(result.issues()).isEmpty();
        assertThat(result.areas()).hasSize(4)
                .allSatisfy(area -> {
                    assertThat(area.status()).isEqualTo(DataQualityHealthStatus.OK);
                    assertThat(area.ready()).isTrue();
                });
        assertThat(result.employeeRating().rankedEmployeeCount()).isOne();
        assertThat(result.payroll().freshness().status())
                .isEqualTo(PayrollFreshnessStatus.CURRENT);
    }

    @Test
    void consolidatesIncompleteSourcesRatingAndPayrollIntoStableReasons() {
        Fixture fixture = fixture();
        when(fixture.dataStatus().status()).thenReturn(StoreDataFreshnessStatus.STALE);
        when(fixture.dataStatus().dataThroughDate()).thenReturn(AS_OF.minusDays(2));
        when(fixture.kpiQuality().completeCostData()).thenReturn(false);
        when(fixture.kpiQuality().unmappedItemCount()).thenReturn(2L);
        when(fixture.kpiQuality().missingCostItemCount()).thenReturn(1L);
        when(fixture.ratingPlan().complete()).thenReturn(false);
        when(fixture.employee().shiftCount()).thenReturn(0L);
        when(fixture.employee().workedHours()).thenReturn(BigDecimal.ZERO.setScale(2));
        when(fixture.employee().netRevenue()).thenReturn(new BigDecimal("100.00"));
        when(fixture.employee().ranked()).thenReturn(false);
        PayrollReadinessView readiness = readiness(
                PayrollReadinessStatus.NEEDS_CORRECTION,
                false,
                2,
                1,
                1
        );
        when(fixture.payrollQuality().inspect(STORE_ID, MONTH)).thenReturn(
                new PayrollPeriodQualitySnapshot(readiness, false, null, null)
        );

        StorePeriodQualityView result = fixture.service().inspect(STORE_ID, MONTH, AS_OF);

        assertThat(result.status()).isEqualTo(DataQualityHealthStatus.ERROR);
        assertThat(result.readyForDecisions()).isFalse();
        assertThat(result.issues()).extracting(PeriodQualityIssueView::code).contains(
                "SOURCE_DATA_STALE",
                "SOURCE_DATA_INCOMPLETE_THROUGH_AS_OF",
                "SOURCE_PRODUCTS_UNMAPPED",
                "SOURCE_COST_DATA_MISSING",
                "RATING_PLAN_COVERAGE_INCOMPLETE",
                "RATING_INPUT_DATA_INCOMPLETE",
                "RATING_NO_EMPLOYEES_WITH_SHIFTS",
                "RATING_SALES_WITHOUT_SHIFT",
                "PAYROLL_PRODUCTS_UNMAPPED",
                "PAYROLL_REQUIRED_COST_MISSING",
                "PAYROLL_DAYS_WITHOUT_SHIFT",
                "PAYROLL_NOT_CALCULATED"
        );
        assertThat(result.issues()).isSortedAccordingTo(
                java.util.Comparator
                        .comparingInt((PeriodQualityIssueView issue) -> switch (issue.severity()) {
                            case ERROR -> 0;
                            case WARNING -> 1;
                            case INFO -> 2;
                        })
                        .thenComparing(PeriodQualityIssueView::area)
                        .thenComparing(PeriodQualityIssueView::code)
        );
    }

    private Fixture fixture() {
        StoreRepository storeRepository = mock(StoreRepository.class);
        Store store = mock(Store.class);
        when(store.getTimezone()).thenReturn("Europe/Kaliningrad");
        when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(store));

        StoreDataStatusService dataStatusService = mock(StoreDataStatusService.class);
        StoreDataStatusView dataStatus = mock(StoreDataStatusView.class);
        when(dataStatus.status()).thenReturn(StoreDataFreshnessStatus.CURRENT);
        when(dataStatus.dataThroughDate()).thenReturn(AS_OF);
        when(dataStatusService.get(STORE_ID)).thenReturn(dataStatus);

        StoreKpiService storeKpiService = mock(StoreKpiService.class);
        StoreKpiResult storeKpi = mock(StoreKpiResult.class);
        StoreKpiDataQuality kpiQuality = mock(StoreKpiDataQuality.class);
        when(kpiQuality.completeCostData()).thenReturn(true);
        when(storeKpi.dataQuality()).thenReturn(kpiQuality);
        when(storeKpiService.calculate(STORE_ID, new StoreKpiPeriod(MONTH.atDay(1), AS_OF)))
                .thenReturn(storeKpi);

        StorePlanProgressService planProgressService = mock(StorePlanProgressService.class);
        StorePlanProgressView progress = mock(StorePlanProgressView.class);
        StorePlanProgressDataQuality planQuality = mock(StorePlanProgressDataQuality.class);
        when(planQuality.completeThroughAsOf()).thenReturn(true);
        when(planQuality.classificationComplete()).thenReturn(true);
        when(progress.dataQuality()).thenReturn(planQuality);
        when(progress.formulaVersion()).thenReturn("store-plan-progress-v1");
        when(planProgressService.find(STORE_ID, MONTH, AS_OF)).thenReturn(Optional.of(progress));

        EmployeeRatingQueryService ratingQueryService = mock(EmployeeRatingQueryService.class);
        EmployeeRatingResult rating = mock(EmployeeRatingResult.class);
        RatingPlanContext ratingPlan = mock(RatingPlanContext.class);
        when(ratingPlan.complete()).thenReturn(true);
        RatingFormulaView formula = mock(RatingFormulaView.class);
        when(formula.version()).thenReturn("employee-rating-v1");
        when(formula.minimumCoveragePercent()).thenReturn(new BigDecimal("75.00"));
        EmployeeRatingEntry employee = mock(EmployeeRatingEntry.class);
        when(employee.ratingEligible()).thenReturn(true);
        when(employee.shiftCount()).thenReturn(1L);
        when(employee.workedHours()).thenReturn(new BigDecimal("11.00"));
        when(employee.netRevenue()).thenReturn(new BigDecimal("100.00"));
        when(employee.ranked()).thenReturn(true);
        RatingScoreBreakdown scores = mock(RatingScoreBreakdown.class);
        when(scores.coveragePercent()).thenReturn(new BigDecimal("100.00"));
        when(employee.scores()).thenReturn(scores);
        when(rating.plan()).thenReturn(ratingPlan);
        when(rating.formula()).thenReturn(formula);
        when(rating.employees()).thenReturn(List.of(employee));
        when(rating.history()).thenReturn(EmployeeRatingHistoryView.live());
        when(ratingQueryService.get(
                STORE_ID, new StoreKpiPeriod(MONTH.atDay(1), AS_OF)
        )).thenReturn(rating);

        PayrollPeriodQualityService payrollQualityService =
                mock(PayrollPeriodQualityService.class);
        PayrollFreshnessView freshness = new PayrollFreshnessView(
                PayrollFreshnessStatus.CURRENT, false, List.of(), NOW
        );
        when(payrollQualityService.inspect(STORE_ID, MONTH)).thenReturn(
                new PayrollPeriodQualitySnapshot(
                        readiness(PayrollReadinessStatus.READY, true, 0, 0, 0),
                        true,
                        PayrollRunStatus.CALCULATED,
                        freshness
                )
        );
        StorePeriodQualityService service = new StorePeriodQualityService(
                storeRepository,
                dataStatusService,
                storeKpiService,
                planProgressService,
                ratingQueryService,
                payrollQualityService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        return new Fixture(
                service,
                dataStatus,
                kpiQuality,
                ratingPlan,
                employee,
                payrollQualityService
        );
    }

    private PayrollReadinessView readiness(
            PayrollReadinessStatus status,
            boolean canApprove,
            int unmapped,
            int missingCosts,
            int daysWithoutShift
    ) {
        return new PayrollReadinessView(
                STORE_ID,
                MONTH.atDay(1),
                status,
                true,
                canApprove,
                true,
                true,
                null,
                10,
                10,
                unmapped,
                missingCosts,
                daysWithoutShift,
                List.of(),
                List.of(),
                List.of()
        );
    }

    private record Fixture(
            StorePeriodQualityService service,
            StoreDataStatusView dataStatus,
            StoreKpiDataQuality kpiQuality,
            RatingPlanContext ratingPlan,
            EmployeeRatingEntry employee,
            PayrollPeriodQualityService payrollQuality
    ) {
    }
}
