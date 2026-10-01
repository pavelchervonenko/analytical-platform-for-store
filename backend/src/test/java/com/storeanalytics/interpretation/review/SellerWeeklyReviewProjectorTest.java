package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse.DateRange;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricState;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.PeriodContext;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.ReportState;
import com.storeanalytics.metrics.repository.EmployeeCategoryKpiAggregate;
import com.storeanalytics.metrics.repository.EmployeeKpiAggregate;
import com.storeanalytics.metrics.repository.SellerDocumentAggregate;
import com.storeanalytics.metrics.service.AttachRateDataQuality;
import com.storeanalytics.metrics.service.AttachRateResult;
import com.storeanalytics.metrics.service.CategoryKpiDataQuality;
import com.storeanalytics.metrics.service.CategoryKpiGroup;
import com.storeanalytics.metrics.service.CategoryKpiMetrics;
import com.storeanalytics.metrics.service.CategoryKpiResult;
import com.storeanalytics.metrics.service.SellerCohortSnapshot;
import com.storeanalytics.metrics.service.SellerPeriodComparisonFacts;
import com.storeanalytics.metrics.service.SellerPeriodFacts;
import com.storeanalytics.metrics.service.SellerPeriodMetrics;
import com.storeanalytics.metrics.service.SellerReturnAttributionQuality;
import com.storeanalytics.product.model.AnalyticsCategoryKind;
import com.storeanalytics.product.model.DeviceFamily;
import com.storeanalytics.store.service.StoreDataStatusView;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SellerWeeklyReviewProjectorTest {

    private static final DateRange CURRENT = new DateRange(
            LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 23));
    private static final DateRange PREVIOUS = new DateRange(
            LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 16));
    private static final UUID STORE = UUID.randomUUID();
    private static final UUID SELLER = UUID.randomUUID();
    private static final SellerCohortSnapshot COHORT = new SellerCohortSnapshot(
            STORE, List.of(SELLER));

    private final SellerWeeklyReviewProjector projector = new SellerWeeklyReviewProjector();

    @Test
    void completeCoverageComposesSellerOnlyResultsAndStructure() {
        StoreDataStatusView status = mock(StoreDataStatusView.class);
        when(status.salesDataThroughDate()).thenReturn(CURRENT.end());
        when(status.returnsDataThroughDate()).thenReturn(CURRENT.end());

        var result = projector.project(source(status, facts("100"), facts("80")));

        assertThat(result.quality().reportState()).isEqualTo(ReportState.READY);
        assertThat(result.core().results().getFirst().current()).isEqualByComparingTo("100");
        assertThat(result.core().results().getFirst().evidenceRefs())
                .containsExactly("SELLERS.NET_REVENUE");
        assertThat(result.structure().root().comparison().evidenceRefs())
                .containsExactly("SELLERS.STRUCTURE.NET_REVENUE.REVENUE");
        assertThat(result.additionalSales().additionalRevenue().current())
                .isEqualByComparingTo("20");
        assertThat(result.teamFacts()).isPresent();
        assertThat(result.teamFacts().orElseThrow().currentNetRevenue()).isEqualByComparingTo("100");
        assertThat(result.teamFacts().orElseThrow().employees()).hasSize(1);
    }

    @Test
    void missingCoverageDoesNotReadOrPublishPartialSellerNumbers() {
        SellerPeriodFacts current = mock(SellerPeriodFacts.class);
        SellerPeriodFacts previous = mock(SellerPeriodFacts.class);
        when(current.metrics()).thenReturn(mock(SellerPeriodMetrics.class));
        when(previous.metrics()).thenReturn(mock(SellerPeriodMetrics.class));

        var result = projector.project(source(mock(StoreDataStatusView.class), current, previous));

        assertThat(result.quality().reportState()).isEqualTo(ReportState.BLOCKED);
        assertThat(result.core().results()).allSatisfy(metric -> {
            assertThat(metric.metricState()).isEqualTo(MetricState.UNAVAILABLE);
            assertThat(metric.current()).isNull();
        });
        assertThat(result.structure().root().comparison().current()).isNull();
        assertThat(result.additionalSales().accessoryRevenue()).isNull();
        assertThat(result.teamFacts()).isEmpty();
    }

    private SellerWeeklyReviewFacts source(
            StoreDataStatusView status, SellerPeriodFacts current, SellerPeriodFacts previous
    ) {
        SellerWeeklyReviewFacts source = mock(SellerWeeklyReviewFacts.class);
        SellerPeriodComparisonFacts comparison = mock(SellerPeriodComparisonFacts.class);
        when(source.sourceDataStatus()).thenReturn(status);
        when(source.sourceStability()).thenReturn(SellerWeeklySourceStability.STABLE);
        SellerWeeklySourceCoverage coverage = status.salesDataThroughDate() == null
                ? new SellerWeeklySourceCoverage(
                        new SellerWeeklySourceCoverage.Window(false, false),
                        new SellerWeeklySourceCoverage.Window(false, false),
                        new SellerWeeklySourceCoverage.Window(false, false))
                : SellerWeeklySourceCoverage.complete();
        when(source.sourceCoverage()).thenReturn(coverage);
        when(source.period()).thenReturn(new PeriodContext("Europe/Moscow", CURRENT,
                PREVIOUS, "Текущая", "Предыдущая"));
        when(source.comparison()).thenReturn(comparison);
        when(comparison.current()).thenReturn(current);
        when(comparison.previous()).thenReturn(previous);
        when(current.returnAttribution()).thenReturn(SellerReturnAttributionQuality.COMPLETE);
        when(previous.returnAttribution()).thenReturn(SellerReturnAttributionQuality.COMPLETE);
        return source;
    }

    private SellerPeriodFacts facts(String net) {
        SellerPeriodFacts result = mock(SellerPeriodFacts.class);
        SellerPeriodMetrics metrics = mock(SellerPeriodMetrics.class);
        CategoryKpiResult categories = mock(CategoryKpiResult.class);
        CategoryKpiMetrics totals = mock(CategoryKpiMetrics.class);
        AttachRateResult attach = mock(AttachRateResult.class);
        when(result.metrics()).thenReturn(metrics);
        when(result.projectedAttachRates()).thenReturn(attach);
        when(result.documents()).thenReturn(List.of(new SellerDocumentAggregate(
                SELLER, new BigDecimal(net), BigDecimal.ZERO, 1, 0, 1)));
        EmployeeKpiAggregate employee = new EmployeeKpiAggregate(SELLER, "Synthetic",
                true, true, true, true, false, new BigDecimal(net), BigDecimal.ONE,
                BigDecimal.ZERO, 1, 0, 0, 0);
        EmployeeCategoryKpiAggregate additional = new EmployeeCategoryKpiAggregate(
                SELLER, "Synthetic", true, true, true, true, false, "ACCESSORY", "Accessory",
                AnalyticsCategoryKind.ACCESSORY, DeviceFamily.NONE, true, false, false, true,
                new BigDecimal("20"), BigDecimal.ONE, BigDecimal.ZERO, 1, 0, 0);
        when(metrics.cohort()).thenReturn(COHORT);
        when(metrics.employees()).thenReturn(List.of(employee));
        when(metrics.employeeCategories()).thenReturn(List.of(additional));
        when(metrics.totals()).thenReturn(totals);
        when(metrics.categories()).thenReturn(categories);
        when(totals.netRevenue()).thenReturn(new BigDecimal(net));
        when(totals.grossProfit()).thenReturn(new BigDecimal(net));
        when(totals.marginPercent()).thenReturn(new BigDecimal("100"));
        when(totals.dataQuality()).thenReturn(new CategoryKpiDataQuality(true, 1, 0, 0));
        when(categories.categories()).thenReturn(List.of());
        List<CategoryKpiGroup> groups = List.of(
                group("PHONES", new BigDecimal(net).subtract(new BigDecimal("20"))),
                group("DEVICES", new BigDecimal(net).subtract(new BigDecimal("20"))),
                group("ADDITIONAL_REVENUE", new BigDecimal("20")),
                group("ACCESSORY", new BigDecimal("10")),
                group("SERVICE", new BigDecimal("10")));
        when(categories.groups()).thenReturn(groups);
        when(attach.dataQuality()).thenReturn(new AttachRateDataQuality(0, 0, 0));
        when(attach.formulaVersion()).thenReturn("attach-rate-v3");
        when(attach.rates()).thenReturn(List.of());
        return result;
    }

    private CategoryKpiGroup group(String code, BigDecimal money) {
        CategoryKpiMetrics metrics = mock(CategoryKpiMetrics.class);
        when(metrics.netRevenue()).thenReturn(money);
        return new CategoryKpiGroup(code, code, metrics);
    }
}
