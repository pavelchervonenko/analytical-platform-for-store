package com.storeanalytics.interpretation.review;

import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.Materiality.NOT_EVALUATED;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricState.LIMITED;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricState.READY;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricState.UNAVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.interpretation.review.WeeklyReviewPolicyV1.RevenuePeriod;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricComparison;
import com.storeanalytics.metrics.service.StoreKpiDataQuality;
import com.storeanalytics.metrics.service.StoreKpiResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WeeklyReviewCoreProjectorTest {

    private final WeeklyReviewCoreProjector projector = new WeeklyReviewCoreProjector();

    @Test
    void returnsCoreMetricsInTheApprovedOrder() {
        WeeklyReviewCoreProjector.Projection result = projector.project(
                kpi("85000.00", "40000.00", "47.06", quality(0, 0)),
                kpi("85000.00", "38000.00", "44.71", quality(0, 0)),
                revenue("100000.00", "15000.00", 8, 2),
                revenue("90000.00", "5000.00", 7, 1)
        );

        assertThat(result.results()).extracting(MetricComparison::code)
                .containsExactly(
                        "NET_REVENUE",
                        "GROSS_PROFIT",
                        "MARGIN_PERCENT",
                        "AVERAGE_SALE"
                );
        assertThat(result.results()).extracting(MetricComparison::metricState)
                .containsOnly(READY);
        assertThat(result.results().get(3).current())
                .isEqualByComparingTo("12500.00");
        assertThat(result.revenueDecomposition().identityValid()).isTrue();
    }

    @Test
    void makesOnlyProfitAndMarginUnavailableWhenCostIsMissing() {
        WeeklyReviewCoreProjector.Projection result = projector.project(
                kpi("85000.00", null, null, quality(1, 0)),
                kpi("85000.00", "38000.00", "44.71", quality(0, 0)),
                revenue("100000.00", "15000.00", 8, 2),
                revenue("90000.00", "5000.00", 7, 1)
        );

        assertThat(result.results().get(0).metricState()).isEqualTo(READY);
        assertThat(result.results().get(1).metricState()).isEqualTo(UNAVAILABLE);
        assertThat(result.results().get(2).metricState()).isEqualTo(UNAVAILABLE);
        assertThat(result.results().get(3).metricState()).isEqualTo(READY);
    }

    @Test
    void keepsUnexpectedZeroCostVisibleButOutOfFactors() {
        WeeklyReviewCoreProjector.Projection result = projector.project(
                kpi("85000.00", "40000.00", "47.06", quality(0, 1)),
                kpi("85000.00", "38000.00", "44.71", quality(0, 0)),
                revenue("100000.00", "15000.00", 8, 2),
                revenue("90000.00", "5000.00", 7, 1)
        );

        assertThat(result.results().get(1).metricState()).isEqualTo(LIMITED);
        assertThat(result.results().get(1).materiality()).isEqualTo(NOT_EVALUATED);
        assertThat(result.results().get(2).metricState()).isEqualTo(LIMITED);
    }

    @Test
    void reportsAverageSaleAsUnavailableWithoutSaleDocuments() {
        WeeklyReviewCoreProjector.Projection result = projector.project(
                kpi("0.00", "0.00", null, quality(0, 0)),
                kpi("85.00", "38.00", "44.71", quality(0, 0)),
                revenue("0.00", "0.00", 0, 0),
                revenue("90.00", "5.00", 1, 1)
        );

        MetricComparison averageSale = result.results().get(3);
        assertThat(averageSale.current()).isNull();
        assertThat(averageSale.metricState()).isEqualTo(UNAVAILABLE);
    }

    @Test
    void rejectsMismatchBetweenStoreKpiAndRevenueBreakdown() {
        assertThatThrownBy(() -> projector.project(
                kpi("90.00", "40.00", "44.44", quality(0, 0)),
                kpi("85.00", "38.00", "44.71", quality(0, 0)),
                revenue("100.00", "15.00", 1, 1),
                revenue("90.00", "5.00", 1, 1)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must match revenue decomposition");
    }

    private StoreKpiResult kpi(
            String revenue,
            String grossProfit,
            String margin,
            StoreKpiDataQuality quality
    ) {
        BigDecimal netRevenue = new BigDecimal(revenue);
        BigDecimal profit = grossProfit == null ? null : new BigDecimal(grossProfit);
        return new StoreKpiResult(
                UUID.randomUUID(),
                LocalDate.of(2026, 8, 17),
                LocalDate.of(2026, 8, 23),
                "store-kpi-v1",
                netRevenue,
                BigDecimal.ONE.setScale(3),
                profit == null ? null : netRevenue.subtract(profit),
                profit,
                margin == null ? null : new BigDecimal(margin),
                quality
        );
    }

    private StoreKpiDataQuality quality(long missingCost, long unexpectedZeroCost) {
        return new StoreKpiDataQuality(
                missingCost == 0,
                1,
                0,
                missingCost,
                unexpectedZeroCost,
                0,
                0
        );
    }

    private RevenuePeriod revenue(
            String sales,
            String returns,
            long saleCount,
            long returnCount
    ) {
        BigDecimal salesAmount = new BigDecimal(sales);
        BigDecimal returnAmount = new BigDecimal(returns);
        return new RevenuePeriod(
                salesAmount,
                returnAmount,
                salesAmount.subtract(returnAmount),
                saleCount,
                returnCount
        );
    }
}
