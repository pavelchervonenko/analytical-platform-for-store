package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse.DateRange;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.ReportState;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.SourceCode;
import com.storeanalytics.metrics.service.CategoryKpiDataQuality;
import com.storeanalytics.metrics.service.CategoryKpiMetrics;
import com.storeanalytics.metrics.service.SellerPeriodMetrics;
import com.storeanalytics.metrics.service.SellerReturnAttributionQuality;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class SellerWeeklyQualityPolicyTest {

    private static final DateRange CURRENT = new DateRange(
            LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 23));
    private static final DateRange PREVIOUS = new DateRange(
            LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 16));

    private final WeeklyReviewQualityPolicyV1 policy = new WeeklyReviewQualityPolicyV1();

    @Test
    void sellerQualityDoesNotInheritUnrelatedStoreIssues() {
        var result = policy.decideSellers(source(CURRENT.end(), CURRENT.end()),
                metrics(0, 0, 3), metrics(0, 0, 0), new WeeklyReviewQualityPolicyV1.AttributionWindows(
                        SellerReturnAttributionQuality.COMPLETE, SellerReturnAttributionQuality.COMPLETE),
                CURRENT, PREVIOUS,
                SellerWeeklySourceStability.STABLE);

        assertThat(result.reportState()).isEqualTo(ReportState.READY);
        assertThat(result.limitations()).isEmpty();
        assertThat(result.sourceCoverage()).extracting(item -> item.sourceCode())
                .containsExactly(SourceCode.SALES, SourceCode.RETURNS);
    }

    @Test
    void scopesClassificationAndMissingCostToSelectedSellers() {
        var result = policy.decideSellers(source(CURRENT.end(), CURRENT.end()),
                metrics(2, 1, 0), metrics(0, 0, 0), new WeeklyReviewQualityPolicyV1.AttributionWindows(
                        SellerReturnAttributionQuality.COMPLETE, SellerReturnAttributionQuality.COMPLETE),
                CURRENT, PREVIOUS,
                SellerWeeklySourceStability.STABLE);

        assertThat(result.reportState()).isEqualTo(ReportState.PARTIAL);
        assertThat(result.limitations()).extracting(item -> item.scope())
                .containsExactly("SELLERS", "SELLERS");
        assertThat(result.limitations()).extracting(item -> item.code())
                .containsExactly("PRODUCTS_UNCLASSIFIED", "COST_DATA_MISSING");
        assertThat(result.limitations().get(0).affectedBlockIds())
                .containsExactly("sales-structure", "additional-sales");
        assertThat(result.limitations().get(1).affectedMetricCodes())
                .containsExactly("GROSS_PROFIT", "MARGIN_PERCENT");
    }

    @Test
    void uncertainReturnsHaveSeparateNonBlockingReasons() {
        var result = policy.decideSellers(source(CURRENT.end(), CURRENT.end()),
                metrics(0, 0, 0), metrics(0, 0, 0),
                new WeeklyReviewQualityPolicyV1.AttributionWindows(
                        new SellerReturnAttributionQuality(2, 1), SellerReturnAttributionQuality.COMPLETE),
                CURRENT, PREVIOUS, SellerWeeklySourceStability.STABLE);

        assertThat(result.reportState()).isEqualTo(ReportState.PARTIAL);
        assertThat(result.qualitySummary().warningCount()).isEqualTo(2);
        assertThat(result.qualitySummary().blockingCount()).isZero();
        assertThat(result.limitations()).extracting(item -> item.code())
                .containsExactly("ORPHAN_RETURN", "RETURN_ORIGINAL_AUTHOR_UNKNOWN");
        assertThat(result.limitations()).allSatisfy(item -> {
            assertThat(item.scope()).isEqualTo("SELLERS");
            assertThat(item.severity()).isEqualTo("WARNING");
            assertThat(item.affectedMetricCodes()).contains("RETURN_REVENUE", "NET_REVENUE");
        });
    }

    @Test
    void currentSourceGapBlocksWithoutPresentingSellerFiguresAsReady() {
        var result = policy.decideSellers(source(PREVIOUS.end(), CURRENT.end()),
                metrics(1, 1, 0), metrics(0, 0, 0), new WeeklyReviewQualityPolicyV1.AttributionWindows(
                        SellerReturnAttributionQuality.COMPLETE, SellerReturnAttributionQuality.COMPLETE),
                CURRENT, PREVIOUS,
                SellerWeeklySourceStability.STABLE);

        assertThat(result.reportState()).isEqualTo(ReportState.BLOCKED);
        assertThat(result.limitations()).singleElement().satisfies(item -> {
            assertThat(item.code()).isEqualTo("SALES_COVERAGE_INCOMPLETE");
            assertThat(item.scope()).isEqualTo("SELLERS");
            assertThat(item.severity()).isEqualTo("BLOCKING");
        });
    }

    @Test
    void currentReturnsGapBlocksRatherThanProducingMisleadingPartialReport() {
        var result = policy.decideSellers(source(CURRENT.end(), LocalDate.of(2026, 8, 20)),
                metrics(0, 0, 0), metrics(0, 0, 0), new WeeklyReviewQualityPolicyV1.AttributionWindows(
                        SellerReturnAttributionQuality.COMPLETE, SellerReturnAttributionQuality.COMPLETE),
                CURRENT, PREVIOUS,
                SellerWeeklySourceStability.STABLE);

        assertThat(result.reportState()).isEqualTo(ReportState.BLOCKED);
        assertThat(result.limitations()).singleElement().satisfies(item -> {
            assertThat(item.code()).isEqualTo("RETURNS_COVERAGE_INCOMPLETE");
            assertThat(item.severity()).isEqualTo("BLOCKING");
        });
    }

    @Test
    void activeSyncBlocksEvenWhenPreviousCoverageLooksComplete() {
        SellerWeeklySourceCoverage status = source(CURRENT.end(), CURRENT.end());
        var result = policy.decideSellers(status, metrics(0, 0, 0), metrics(0, 0, 0),
                new WeeklyReviewQualityPolicyV1.AttributionWindows(
                        SellerReturnAttributionQuality.COMPLETE, SellerReturnAttributionQuality.COMPLETE),
                CURRENT, PREVIOUS, SellerWeeklySourceStability.IN_PROGRESS);

        assertThat(result.reportState()).isEqualTo(ReportState.BLOCKED);
        assertThat(result.limitations()).singleElement().satisfies(item -> {
            assertThat(item.code()).isEqualTo("SOURCE_SYNC_IN_PROGRESS");
            assertThat(item.scope()).isEqualTo("SELLERS");
            assertThat(item.severity()).isEqualTo("BLOCKING");
        });
    }

    @Test
    void failedSyncBlocksUntilReconciliationDespiteCompleteCoverage() {
        SellerWeeklySourceCoverage status = source(CURRENT.end(), CURRENT.end());
        var result = policy.decideSellers(status, metrics(0, 0, 0), metrics(0, 0, 0),
                new WeeklyReviewQualityPolicyV1.AttributionWindows(
                        SellerReturnAttributionQuality.COMPLETE, SellerReturnAttributionQuality.COMPLETE),
                CURRENT, PREVIOUS, SellerWeeklySourceStability.NEEDS_RECONCILIATION);

        assertThat(result.reportState()).isEqualTo(ReportState.BLOCKED);
        assertThat(result.limitations()).singleElement().satisfies(item ->
                assertThat(item.code()).isEqualTo("SOURCE_RECONCILIATION_PENDING"));
    }

    @Test
    void missingOrdersCoverageBlocksSellerTotalsAndComparisons() {
        SellerWeeklySourceCoverage coverage = new SellerWeeklySourceCoverage(
                new SellerWeeklySourceCoverage.Window(true, true),
                new SellerWeeklySourceCoverage.Window(true, true),
                new SellerWeeklySourceCoverage.Window(false, true));

        var result = policy.decideSellers(coverage, metrics(0, 0, 0), metrics(0, 0, 0),
                new WeeklyReviewQualityPolicyV1.AttributionWindows(
                        SellerReturnAttributionQuality.COMPLETE, SellerReturnAttributionQuality.COMPLETE),
                CURRENT, PREVIOUS, SellerWeeklySourceStability.STABLE);

        assertThat(result.reportState()).isEqualTo(ReportState.BLOCKED);
        assertThat(result.limitations()).singleElement().satisfies(item -> {
            assertThat(item.code()).isEqualTo("ORDERS_COVERAGE_INCOMPLETE");
            assertThat(item.severity()).isEqualTo("BLOCKING");
        });
    }

    @Test
    void previousWeekGapAlsoBlocksComparisons() {
        SellerWeeklySourceCoverage coverage = new SellerWeeklySourceCoverage(
                new SellerWeeklySourceCoverage.Window(true, false),
                new SellerWeeklySourceCoverage.Window(true, true),
                new SellerWeeklySourceCoverage.Window(true, true));

        var result = policy.decideSellers(coverage, metrics(0, 0, 0), metrics(0, 0, 0),
                new WeeklyReviewQualityPolicyV1.AttributionWindows(
                        SellerReturnAttributionQuality.COMPLETE, SellerReturnAttributionQuality.COMPLETE),
                CURRENT, PREVIOUS, SellerWeeklySourceStability.STABLE);

        assertThat(result.reportState()).isEqualTo(ReportState.BLOCKED);
        assertThat(result.limitations()).singleElement().satisfies(item -> {
            assertThat(item.code()).isEqualTo("SALES_COVERAGE_INCOMPLETE");
            assertThat(item.period()).isEqualTo(PREVIOUS);
        });
    }

    private SellerWeeklySourceCoverage source(LocalDate salesThrough, LocalDate returnsThrough) {
        return new SellerWeeklySourceCoverage(window(salesThrough), window(returnsThrough),
                new SellerWeeklySourceCoverage.Window(true, true));
    }

    private SellerWeeklySourceCoverage.Window window(LocalDate through) {
        return new SellerWeeklySourceCoverage.Window(
                !through.isBefore(CURRENT.end()), !through.isBefore(PREVIOUS.end()));
    }

    private SellerPeriodMetrics metrics(long unmapped, long missingCost, long zeroCost) {
        SellerPeriodMetrics metrics = mock(SellerPeriodMetrics.class);
        CategoryKpiMetrics totals = mock(CategoryKpiMetrics.class);
        when(metrics.unmappedItemCount()).thenReturn(unmapped);
        when(metrics.totals()).thenReturn(totals);
        when(totals.dataQuality()).thenReturn(new CategoryKpiDataQuality(
                missingCost == 0, 10, missingCost, zeroCost));
        return metrics;
    }
}
