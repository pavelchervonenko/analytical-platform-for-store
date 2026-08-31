package com.storeanalytics.performance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeanalytics.common.exception.InvalidRequestException;
import com.storeanalytics.metrics.service.OverviewCommercialMetric;
import com.storeanalytics.metrics.service.OverviewMetricScope;
import com.storeanalytics.metrics.service.OverviewMetricsDataQuality;
import com.storeanalytics.metrics.service.OverviewMetricsResult;
import com.storeanalytics.metrics.service.OverviewMetricsService;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import com.storeanalytics.performance.repository.StorePlanDailyActual;
import com.storeanalytics.performance.repository.StorePlanDailyActualRepository;
import com.storeanalytics.store.service.StoreDataFreshnessStatus;
import com.storeanalytics.store.service.StoreDataStatusService;
import com.storeanalytics.store.service.StoreDataStatusView;
import com.storeanalytics.store.service.StoreSyncActivityView;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StorePlanProgressServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-23T10:00:00Z");

    private StorePerformancePlanService planService;
    private OverviewMetricsService overviewMetricsService;
    private StoreDataStatusService dataStatusService;
    private StorePlanDailyActualRepository dailyActualRepository;
    private StorePlanProgressService service;

    @BeforeEach
    void setUp() {
        planService = mock(StorePerformancePlanService.class);
        overviewMetricsService = mock(OverviewMetricsService.class);
        dailyActualRepository = mock(StorePlanDailyActualRepository.class);
        dataStatusService = mock(StoreDataStatusService.class);
        service = new StorePlanProgressService(
                planService,
                overviewMetricsService,
                dailyActualRepository,
                dataStatusService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void calculatesAmountSharePaceForecastAndFocusIndependently() {
        UUID storeId = UUID.randomUUID();
        YearMonth month = YearMonth.of(2026, 7);
        LocalDate asOf = LocalDate.of(2026, 7, 20);
        StoreKpiPeriod period = new StoreKpiPeriod(month.atDay(1), asOf);
        when(planService.get(storeId, month)).thenReturn(plan(storeId));
        when(overviewMetricsService.calculate(
                storeId, period, OverviewMetricScope.STORE
        )).thenReturn(overviewMetrics(
                storeId, period, "15840000.00", "520000.00", "270000.00", 2
        ));
        when(dataStatusService.get(storeId)).thenReturn(dataStatus(
                storeId, asOf, StoreDataFreshnessStatus.CURRENT, 1
        ));
        when(dailyActualRepository.aggregate(
                storeId, month.atDay(1), asOf, OverviewMetricScope.STORE
        ))
                .thenReturn(List.of(new StorePlanDailyActual(
                        asOf,
                        new BigDecimal("15840000.00"),
                        new BigDecimal("520000.00"),
                        new BigDecimal("270000.00")
                )));


        StorePlanProgressView result = service.calculate(storeId, month, asOf);

        StorePlanDirectionView revenue = direction(result, StorePlanDirectionCode.REVENUE);
        assertThat(revenue.targetAmount()).isEqualByComparingTo("24000000.00");
        assertThat(revenue.amountCompletionPercent()).isEqualByComparingTo("66.00");
        assertThat(revenue.remainingAmount()).isEqualByComparingTo("8160000.00");
        assertThat(revenue.requiredPerRemainingDay()).isEqualByComparingTo("741818.18");
        assertThat(revenue.projectedAmount()).isEqualByComparingTo("24552000.00");
        assertThat(revenue.status()).isEqualTo(StorePlanProgressStatus.ON_TRACK);

        StorePlanDirectionView accessory = direction(
                result, StorePlanDirectionCode.ACCESSORY
        );
        assertThat(accessory.targetAmount()).isEqualByComparingTo("617760.00");
        assertThat(accessory.amountCompletionPercent()).isEqualByComparingTo("84.18");
        assertThat(accessory.actualSharePercent()).isEqualByComparingTo("3.28");
        assertThat(accessory.shareGapPercentagePoints()).isEqualByComparingTo("-0.62");
        assertThat(accessory.criterionCompletionPercent()).isEqualByComparingTo("84.18");
        assertThat(accessory.requiredPerRemainingDay()).isEqualByComparingTo("8887.27");
        assertThat(accessory.achieved()).isFalse();
        assertThat(accessory.status()).isEqualTo(StorePlanProgressStatus.AT_RISK);

        assertThat(result.dailyTargets()).hasSize(31);
        StorePlanDailyTargetView completedDay = result.dailyTargets().get(19);
        assertThat(completedDay.completed()).isTrue();
        assertThat(completedDay.revenueBasisProjected()).isFalse();
        assertThat(completedDay.accessory().actualSharePercent())
                .isEqualByComparingTo("3.28");
        assertThat(completedDay.accessory().cumulativeGapAmount())
                .isEqualByComparingTo("-97760.00");

        StorePlanDailyTargetView nextDay = result.dailyTargets().get(20);
        assertThat(nextDay.completed()).isFalse();
        assertThat(nextDay.revenueBasisProjected()).isTrue();
        assertThat(nextDay.revenueBasisAmount()).isEqualByComparingTo("792000.00");
        assertThat(nextDay.accessory().targetAmount())
                .isEqualByComparingTo("39775.27");
        assertThat(nextDay.accessory().targetSharePercent())
                .isEqualByComparingTo("5.02");
        assertThat(nextDay.service().targetAmount())
                .isEqualByComparingTo("42414.54");
        assertThat(result.dailyTargets().stream()
                .filter(target -> !target.completed())
                .map(target -> target.accessory().targetAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("437528.00");


        assertThat(result.totalDays()).isEqualTo(31);
        assertThat(result.elapsedDays()).isEqualTo(20);
        assertThat(result.remainingDays()).isEqualTo(11);
        assertThat(result.achievedDirectionCount()).isZero();
        assertThat(result.focusDirections()).containsExactly(
                StorePlanDirectionCode.ACCESSORY,
                StorePlanDirectionCode.SERVICE,
                StorePlanDirectionCode.ADDITIONAL
        );
        assertThat(result.dataQuality().completeThroughAsOf()).isTrue();
        assertThat(result.dataQuality().classificationComplete()).isFalse();
        assertThat(result.dataQuality().unmappedItemCount()).isEqualTo(2);
        assertThat(result.dataQuality().openQualityIssueCount()).isEqualTo(3);
        assertThat(result.calculatedAt()).isEqualTo(NOW);
    }
    @Test
    void lowersRemainingDailyShareWhenMonthIsAheadOfTarget() {
        UUID storeId = UUID.randomUUID();
        YearMonth month = YearMonth.of(2026, 7);
        LocalDate asOf = LocalDate.of(2026, 7, 10);
        StoreKpiPeriod period = new StoreKpiPeriod(month.atDay(1), asOf);
        when(planService.get(storeId, month)).thenReturn(plan(storeId));
        when(overviewMetricsService.calculate(
                storeId, period, OverviewMetricScope.STORE
        )).thenReturn(overviewMetrics(
                storeId, period, "1000.00", "100.00", "0.00", 0
        ));
        when(dataStatusService.get(storeId)).thenReturn(dataStatus(
                storeId, asOf, StoreDataFreshnessStatus.CURRENT, 0
        ));
        when(dailyActualRepository.aggregate(
                storeId, month.atDay(1), asOf, OverviewMetricScope.STORE
        ))
                .thenReturn(List.of(new StorePlanDailyActual(
                        asOf,
                        new BigDecimal("1000.00"),
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO
                )));

        StorePlanProgressView result = service.calculate(storeId, month, asOf);

        StorePlanDailyTargetView nextDay = result.dailyTargets().get(10);
        assertThat(nextDay.accessory().targetAmount()).isEqualByComparingTo("0.99");
        assertThat(nextDay.accessory().targetSharePercent()).isEqualByComparingTo("0.99");
        assertThat(nextDay.accessory().targetSharePercent())
                .isLessThan(plan(storeId).accessoryShareTarget());
    }



    @Test
    void marksUnmetDirectionsMissedAtMonthEndWithoutInventingShares() {
        UUID storeId = UUID.randomUUID();
        YearMonth month = YearMonth.of(2026, 7);
        LocalDate asOf = month.atEndOfMonth();
        StoreKpiPeriod period = new StoreKpiPeriod(month.atDay(1), asOf);
        when(planService.get(storeId, month)).thenReturn(plan(storeId));
        when(overviewMetricsService.calculate(
                storeId, period, OverviewMetricScope.STORE
        )).thenReturn(overviewMetrics(
                storeId, period, "0.00", "0.00", "0.00", 0
        ));
        when(dataStatusService.get(storeId)).thenReturn(dataStatus(
                storeId, asOf, StoreDataFreshnessStatus.CURRENT, 0
        ));

        StorePlanProgressView result = service.calculate(storeId, month, asOf);

        assertThat(result.directions())
                .allMatch(direction -> direction.status() == StorePlanProgressStatus.MISSED);
        assertThat(direction(result, StorePlanDirectionCode.ACCESSORY).actualSharePercent())
                .isNull();
        assertThat(direction(result, StorePlanDirectionCode.REVENUE)
                .requiredPerRemainingDay()).isNull();
        assertThat(result.focusDirections()).containsExactlyElementsOf(
                List.of(StorePlanDirectionCode.values())
        );
    }

    @Test
    void clampsCurrentMonthProgressToTheLastCompletedDay() {
        UUID storeId = UUID.randomUUID();
        YearMonth month = YearMonth.of(2026, 7);
        LocalDate requestedAsOf = LocalDate.of(2026, 7, 21);
        LocalDate completedThrough = LocalDate.of(2026, 7, 20);
        StoreKpiPeriod completedPeriod = new StoreKpiPeriod(month.atDay(1), completedThrough);
        when(planService.get(storeId, month)).thenReturn(plan(storeId));
        when(dataStatusService.get(storeId)).thenReturn(dataStatus(
                storeId, completedThrough, StoreDataFreshnessStatus.CURRENT, 0
        ));
        when(overviewMetricsService.calculate(
                storeId, completedPeriod, OverviewMetricScope.STORE
        )).thenReturn(overviewMetrics(
                storeId, completedPeriod, "100.00", "0.00", "0.00", 0
        ));

        StorePlanProgressView result = service.calculate(storeId, month, requestedAsOf);

        assertThat(result.asOfDate()).isEqualTo(completedThrough);
        assertThat(result.elapsedDays()).isEqualTo(20);
        assertThat(result.dataQuality().completeThroughAsOf()).isTrue();
    }

    @Test
    void rejectsCutoffOutsideRequestedMonth() {
        assertThatThrownBy(() -> service.calculate(
                UUID.randomUUID(),
                YearMonth.of(2026, 7),
                LocalDate.of(2026, 8, 1)
        )).isInstanceOf(InvalidRequestException.class)
                .hasMessage("asOf must be inside the requested month");
    }

    @Test
    void appliesTheSamePlanToTheRequestedSellerScope() {
        UUID storeId = UUID.randomUUID();
        YearMonth month = YearMonth.of(2026, 7);
        LocalDate asOf = LocalDate.of(2026, 7, 20);
        StoreKpiPeriod period = new StoreKpiPeriod(month.atDay(1), asOf);
        when(planService.get(storeId, month)).thenReturn(plan(storeId));
        when(dataStatusService.get(storeId)).thenReturn(dataStatus(
                storeId, asOf, StoreDataFreshnessStatus.CURRENT, 0
        ));
        when(overviewMetricsService.calculate(
                storeId, period, OverviewMetricScope.SELLERS
        )).thenReturn(overviewMetrics(
                storeId, period, "700.00", "70.00", "35.00", 0
        ));
        when(dailyActualRepository.aggregate(
                storeId, month.atDay(1), asOf, OverviewMetricScope.SELLERS
        )).thenReturn(List.of());

        StorePlanProgressView result = service.calculate(
                storeId, month, asOf, OverviewMetricScope.SELLERS
        );

        assertThat(direction(result, StorePlanDirectionCode.REVENUE).actualAmount())
                .isEqualByComparingTo("700.00");
        assertThat(direction(result, StorePlanDirectionCode.ADDITIONAL).actualAmount())
                .isEqualByComparingTo("105.00");
        verify(overviewMetricsService).calculate(
                storeId, period, OverviewMetricScope.SELLERS
        );
        verify(dailyActualRepository).aggregate(
                storeId, month.atDay(1), asOf, OverviewMetricScope.SELLERS
        );
    }

    private StorePlanDirectionView direction(
            StorePlanProgressView result,
            StorePlanDirectionCode code
    ) {
        return result.directions().stream()
                .filter(direction -> direction.code() == code)
                .findFirst()
                .orElseThrow();
    }

    private StorePerformancePlanView plan(UUID storeId) {
        return new StorePerformancePlanView(
                UUID.randomUUID(),
                storeId,
                LocalDate.of(2026, 7, 1),
                new BigDecimal("24000000.00"),
                new BigDecimal("3.90"),
                new BigDecimal("3.00"),
                new BigDecimal("7.00"),
                UUID.randomUUID(),
                1,
                NOW
        );
    }

    private OverviewMetricsResult overviewMetrics(
            UUID storeId,
            StoreKpiPeriod period,
            String revenue,
            String accessories,
            String services,
            long unmapped
    ) {
        BigDecimal netRevenue = new BigDecimal(revenue);
        BigDecimal accessoryAmount = new BigDecimal(accessories);
        BigDecimal serviceAmount = new BigDecimal(services);
        return new OverviewMetricsResult(
                storeId,
                period.start(),
                period.end(),
                OverviewMetricScope.STORE,
                "overview-metrics-v1",
                netRevenue,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                netRevenue,
                null,
                commercial(accessoryAmount.add(serviceAmount), netRevenue),
                commercial(accessoryAmount, netRevenue),
                commercial(serviceAmount, netRevenue),
                new OverviewMetricsDataQuality(
                        true,
                        10,
                        unmapped,
                        0,
                        0,
                        3,
                        0,
                        true
                )
        );
    }

    private OverviewCommercialMetric commercial(
            BigDecimal amount,
            BigDecimal revenue
    ) {
        BigDecimal share = revenue.signum() == 0
                ? null
                : amount.multiply(BigDecimal.valueOf(100))
                        .divide(revenue, 2, java.math.RoundingMode.HALF_UP);
        return new OverviewCommercialMetric(amount, BigDecimal.ZERO, share);
    }

    private StoreDataStatusView dataStatus(
            UUID storeId,
            LocalDate dataThrough,
            StoreDataFreshnessStatus status,
            long openIssues
    ) {
        return new StoreDataStatusView(
                storeId,
                status,
                dataThrough,
                dataThrough,
                dataThrough,
                dataThrough,
                0,
                NOW,
                new StoreSyncActivityView(false, null, null, null, null, null, null),
                openIssues,
                null,
                null,
                NOW
        );
    }
}
