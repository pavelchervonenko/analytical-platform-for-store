package com.storeanalytics.interpretation.review;

import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricState.READY;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricState.UNAVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.storeanalytics.interpretation.review.WeeklyReviewPolicyV1.RevenuePeriod;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricComparison;
import com.storeanalytics.metrics.repository.SellerDocumentAggregate;
import com.storeanalytics.metrics.service.CategoryKpiDataQuality;
import com.storeanalytics.metrics.service.CategoryKpiMetrics;
import com.storeanalytics.metrics.service.CategoryKpiResult;
import com.storeanalytics.metrics.service.SellerCohortSnapshot;
import com.storeanalytics.metrics.service.SellerPeriodFacts;
import com.storeanalytics.metrics.service.SellerPeriodMetrics;
import com.storeanalytics.metrics.service.StoreKpiDataQuality;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import com.storeanalytics.metrics.service.StoreKpiResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WeeklyReviewCoreProjectorTest {

    private final WeeklyReviewCoreProjector projector = new WeeklyReviewCoreProjector();

    @Test
    void sellerCoreUsesSelectedDocumentsAndSellerEvidenceWithoutStoreValues() {
        WeeklyReviewCoreProjector.Projection result = projector.projectSellers(
                sellerFacts("100", "20", 2, false),
                sellerFacts("80", "0", 1, false),
                false, true
        );

        assertThat(result.results()).extracting(MetricComparison::code).containsExactly(
                "NET_REVENUE", "GROSS_PROFIT", "MARGIN_PERCENT", "AVERAGE_SALE");
        assertThat(result.results().getFirst().current()).isEqualByComparingTo("80");
        assertThat(result.results().getFirst().evidenceRefs())
                .containsExactly("SELLERS.NET_REVENUE");
        assertThat(result.results().getLast().current()).isEqualByComparingTo("50");
        assertThat(result.revenueDecomposition().saleDocumentCount().current())
                .isEqualByComparingTo("2");
        assertThat(result.revenueDecomposition().salesRevenue().evidenceRefs())
                .containsExactly("SELLERS.SALES_REVENUE");
        assertThat(result.revenueDecomposition().identityValid()).isTrue();
    }

    @Test
    void sellerReturnOnlyAndMissingCostKeepTheirOriginalStates() {
        WeeklyReviewCoreProjector.Projection result = projector.projectSellers(
                sellerFacts("0", "20", 0, true),
                sellerFacts("80", "0", 1, false),
                false, true
        );

        assertThat(result.results().getFirst().current()).isEqualByComparingTo("-20");
        assertThat(result.results().get(1).metricState()).isEqualTo(UNAVAILABLE);
        assertThat(result.results().get(2).metricState()).isEqualTo(UNAVAILABLE);
        assertThat(result.results().get(3).metricState()).isEqualTo(UNAVAILABLE);
        assertThat(result.revenueDecomposition().returnRevenue().current())
                .isEqualByComparingTo("20");
    }

    @Test
    void blockedSellerSourceDoesNotPublishEmptyCohortAsZeroSales() {
        SellerPeriodFacts empty = sellerFacts("0", "0", 0, false);
        WeeklyReviewCoreProjector.Projection result = projector.projectSellers(
                empty, empty, true, false);

        assertThat(result.results()).allSatisfy(metric -> {
            assertThat(metric.metricState()).isEqualTo(UNAVAILABLE);
            assertThat(metric.current()).isNull();
            assertThat(metric.evidenceRefs()).allMatch(ref -> ref.startsWith("SELLERS."));
        });
    }

    @Test
    void blockedSellerCoreDoesNotRequireIncompleteFinancialFacts() {
        WeeklyReviewCoreProjector.Projection result = projector.projectSellers(
                mock(SellerPeriodFacts.class), mock(SellerPeriodFacts.class), true, false);

        assertThat(result.results()).allSatisfy(metric -> {
            assertThat(metric.metricState()).isEqualTo(UNAVAILABLE);
            assertThat(metric.current()).isNull();
        });
    }

    @Test
    void sellerRevenueIsLimitedWhenCoverageQualityIsIncomplete() {
        SellerPeriodFacts selected = sellerFacts("100", "20", 2, false);
        WeeklyReviewCoreProjector.Projection result = projector.projectSellers(
                selected, selected, false, false);

        assertThat(result.results().getFirst().metricState())
                .isEqualTo(WeeklyReviewResponse.MetricState.LIMITED);
        assertThat(result.revenueDecomposition().salesRevenue().metricState())
                .isEqualTo(WeeklyReviewResponse.MetricState.LIMITED);
    }

    @Test
    void unknownReturnAttributionLimitsOnlyReturnDependentSellerMetrics() {
        WeeklyReviewCoreProjector.Projection result = projector.projectSellers(
                sellerFacts("100", "20", 2, false), sellerFacts("80", "0", 1, false),
                false, true, false);

        assertThat(result.results().subList(0, 3)).allSatisfy(item ->
                assertThat(item.metricState()).isEqualTo(WeeklyReviewResponse.MetricState.LIMITED));
        assertThat(result.results().get(3).metricState()).isEqualTo(READY);
        assertThat(result.revenueDecomposition().salesRevenue().metricState()).isEqualTo(READY);
        assertThat(result.revenueDecomposition().saleDocumentCount().metricState()).isEqualTo(READY);
        assertThat(result.revenueDecomposition().returnRevenue().metricState())
                .isEqualTo(WeeklyReviewResponse.MetricState.LIMITED);
        assertThat(result.revenueDecomposition().netRevenue().metricState())
                .isEqualTo(WeeklyReviewResponse.MetricState.LIMITED);
    }

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
    void treatsZeroCostAsAValidBusinessValue() {
        WeeklyReviewCoreProjector.Projection result = projector.project(
                kpi("85000.00", "40000.00", "47.06", quality(0, 1)),
                kpi("85000.00", "38000.00", "44.71", quality(0, 0)),
                revenue("100000.00", "15000.00", 8, 2),
                revenue("90000.00", "5000.00", 7, 1)
        );

        assertThat(result.results().get(1).metricState()).isEqualTo(READY);
        assertThat(result.results().get(2).metricState()).isEqualTo(READY);
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
    void neverExposesComputedZerosWhenRequiredSourceCoverageIsBlocked() {
        WeeklyReviewCoreProjector.Projection result = projector.project(
                kpi("0.00", "0.00", null, quality(0, 0)),
                kpi("0.00", "0.00", null, quality(0, 0)),
                revenue("0.00", "0.00", 0, 0),
                revenue("0.00", "0.00", 0, 0),
                true
        );

        assertThat(result.results()).allSatisfy(metric -> {
            assertThat(metric.metricState()).isEqualTo(UNAVAILABLE);
            assertThat(metric.current()).isNull();
            assertThat(metric.previous()).isNull();
        });
        assertThat(List.of(
                result.revenueDecomposition().salesRevenue(),
                result.revenueDecomposition().returnRevenue(),
                result.revenueDecomposition().netRevenue(),
                result.revenueDecomposition().saleDocumentCount(),
                result.revenueDecomposition().returnDocumentCount()
        )).allSatisfy(metric -> {
            assertThat(metric.metricState()).isEqualTo(UNAVAILABLE);
            assertThat(metric.current()).isNull();
            assertThat(metric.previous()).isNull();
        });
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

    private SellerPeriodFacts sellerFacts(
            String sales, String returns, long saleCount, boolean missingCost
    ) {
        UUID storeId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        StoreKpiPeriod period = new StoreKpiPeriod(
                LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 23));
        BigDecimal net = new BigDecimal(sales).subtract(new BigDecimal(returns));
        BigDecimal cost = missingCost ? null : new BigDecimal("40");
        BigDecimal profit = cost == null ? null : net.subtract(cost);
        BigDecimal margin = profit == null || net.signum() == 0 ? null
                : profit.multiply(BigDecimal.valueOf(100))
                        .divide(net, 2, java.math.RoundingMode.HALF_UP);
        CategoryKpiMetrics totals = new CategoryKpiMetrics(
                net, BigDecimal.ONE.setScale(3), cost, profit, profit, margin,
                new CategoryKpiDataQuality(!missingCost, 1, missingCost ? 1 : 0, 0));
        SellerCohortSnapshot cohort = new SellerCohortSnapshot(storeId, List.of(employeeId));
        SellerPeriodMetrics metrics = new SellerPeriodMetrics(cohort, period, totals, 0,
                new CategoryKpiResult(storeId, period.start(), period.end(), "category-kpi-v3",
                        List.of(), List.of()),
                List.of(), List.of());
        return new SellerPeriodFacts(metrics, List.of(new SellerDocumentAggregate(
                employeeId, new BigDecimal(sales), new BigDecimal(returns), saleCount,
                new BigDecimal(returns).signum() > 0 ? 1 : 0, saleCount)), List.of(),
                "attach-rate-v3", com.storeanalytics.metrics.service.SellerReturnAttributionQuality.COMPLETE);
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
