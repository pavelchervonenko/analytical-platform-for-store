package com.storeanalytics.performance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeanalytics.common.exception.InvalidRequestException;
import com.storeanalytics.metrics.service.CategoryKpiDataQuality;
import com.storeanalytics.metrics.service.CategoryKpiEntry;
import com.storeanalytics.metrics.service.CategoryKpiMetrics;
import com.storeanalytics.metrics.service.CategoryKpiResult;
import com.storeanalytics.metrics.service.CategoryKpiService;
import com.storeanalytics.metrics.service.StoreKpiDataQuality;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import com.storeanalytics.metrics.service.StoreKpiResult;
import com.storeanalytics.metrics.service.StoreKpiService;
import com.storeanalytics.product.model.AnalyticsCategoryKind;
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
    private StoreKpiService storeKpiService;
    private CategoryKpiService categoryKpiService;
    private StoreDataStatusService dataStatusService;
    private StorePlanProgressService service;

    @BeforeEach
    void setUp() {
        planService = mock(StorePerformancePlanService.class);
        storeKpiService = mock(StoreKpiService.class);
        categoryKpiService = mock(CategoryKpiService.class);
        dataStatusService = mock(StoreDataStatusService.class);
        service = new StorePlanProgressService(
                planService,
                storeKpiService,
                categoryKpiService,
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
        when(storeKpiService.calculate(storeId, period)).thenReturn(storeKpi(
                storeId, period, "15840000.00", 2
        ));
        when(categoryKpiService.calculate(storeId, period)).thenReturn(categoryKpi(
                storeId,
                period,
                category("ACCESSORIES", AnalyticsCategoryKind.ACCESSORY, "520000.00", true),
                category("SERVICES", AnalyticsCategoryKind.SERVICE, "270000.00", true)
        ));
        when(dataStatusService.get(storeId)).thenReturn(dataStatus(
                storeId, asOf, StoreDataFreshnessStatus.CURRENT, 1
        ));

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
        assertThat(accessory.targetAmount()).isEqualByComparingTo("936000.00");
        assertThat(accessory.amountCompletionPercent()).isEqualByComparingTo("55.56");
        assertThat(accessory.actualSharePercent()).isEqualByComparingTo("3.28");
        assertThat(accessory.shareGapPercentagePoints()).isEqualByComparingTo("-0.62");
        assertThat(accessory.criterionCompletionPercent()).isEqualByComparingTo("84.18");
        assertThat(accessory.requiredPerRemainingDay()).isEqualByComparingTo("37818.18");
        assertThat(accessory.achieved()).isFalse();
        assertThat(accessory.status()).isEqualTo(StorePlanProgressStatus.AT_RISK);

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
        assertThat(result.calculatedAt()).isEqualTo(NOW);
    }

    @Test
    void marksUnmetDirectionsMissedAtMonthEndWithoutInventingShares() {
        UUID storeId = UUID.randomUUID();
        YearMonth month = YearMonth.of(2026, 7);
        LocalDate asOf = month.atEndOfMonth();
        StoreKpiPeriod period = new StoreKpiPeriod(month.atDay(1), asOf);
        when(planService.get(storeId, month)).thenReturn(plan(storeId));
        when(storeKpiService.calculate(storeId, period)).thenReturn(storeKpi(
                storeId, period, "0.00", 0
        ));
        when(categoryKpiService.calculate(storeId, period)).thenReturn(categoryKpi(
                storeId, period
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
    void rejectsCutoffOutsideRequestedMonth() {
        assertThatThrownBy(() -> service.calculate(
                UUID.randomUUID(),
                YearMonth.of(2026, 7),
                LocalDate.of(2026, 8, 1)
        )).isInstanceOf(InvalidRequestException.class)
                .hasMessage("asOf must be inside the requested month");
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

    private StoreKpiResult storeKpi(
            UUID storeId,
            StoreKpiPeriod period,
            String revenue,
            long unmapped
    ) {
        return new StoreKpiResult(
                storeId,
                period.start(),
                period.end(),
                "store-kpi-v1",
                new BigDecimal(revenue),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal(revenue),
                null,
                new StoreKpiDataQuality(true, 10, unmapped, 0, 0, 0)
        );
    }

    private CategoryKpiResult categoryKpi(
            UUID storeId,
            StoreKpiPeriod period,
            CategoryKpiEntry... categories
    ) {
        return new CategoryKpiResult(
                storeId,
                period.start(),
                period.end(),
                "category-kpi-v1",
                List.of(),
                List.of(categories)
        );
    }

    private CategoryKpiEntry category(
            String code,
            AnalyticsCategoryKind kind,
            String revenue,
            boolean additional
    ) {
        return new CategoryKpiEntry(
                code,
                code,
                kind,
                null,
                true,
                false,
                false,
                additional,
                new CategoryKpiMetrics(
                        new BigDecimal(revenue),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        new BigDecimal(revenue),
                        null,
                        new CategoryKpiDataQuality(true, 1, 0, 0)
                )
        );
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
