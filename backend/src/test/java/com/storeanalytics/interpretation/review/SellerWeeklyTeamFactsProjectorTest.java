package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeanalytics.metrics.repository.EmployeeCategoryKpiAggregate;
import com.storeanalytics.metrics.repository.EmployeeKpiAggregate;
import com.storeanalytics.metrics.repository.SellerDocumentAggregate;
import com.storeanalytics.metrics.service.CategoryKpiGroup;
import com.storeanalytics.metrics.service.CategoryKpiMetrics;
import com.storeanalytics.metrics.service.CategoryKpiResult;
import com.storeanalytics.metrics.service.SellerCohortSnapshot;
import com.storeanalytics.metrics.service.SellerPeriodComparisonFacts;
import com.storeanalytics.metrics.service.SellerPeriodFacts;
import com.storeanalytics.metrics.service.SellerPeriodMetrics;
import com.storeanalytics.product.model.AnalyticsCategoryKind;
import com.storeanalytics.product.model.DeviceFamily;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SellerWeeklyTeamFactsProjectorTest {

    private static final UUID STORE = UUID.randomUUID();

    private final SellerWeeklyTeamFactsProjector projector = new SellerWeeklyTeamFactsProjector();

    @Test
    void displayLimitDoesNotTruncateTeamRevenueOrAdditionalSales() {
        List<UUID> ids = IntStream.range(0, 101)
                .mapToObj(index -> new UUID(0, index + 1)).toList();
        SellerCohortSnapshot cohort = new SellerCohortSnapshot(STORE, ids);
        SellerPeriodFacts current = period(cohort, ids.stream()
                .map(id -> employee(id, "1.01", 1)).toList(), ids.stream()
                .map(id -> sale(id, "1.01")).toList(), ids.stream()
                .map(id -> additional(id, "0.20")).toList(), "102.01", "20.20");
        SellerPeriodFacts previous = period(cohort, ids.stream()
                .map(id -> employee(id, "0.50", 1)).toList(), ids.stream()
                .map(id -> sale(id, "0.50")).toList(), ids.stream()
                .map(id -> additional(id, "0.10")).toList(), "50.50", "10.10");

        var team = projector.project(new SellerPeriodComparisonFacts(current, previous));
        var window = team.displayWindow(team.financiallyActiveIds());

        assertThat(team.employees()).hasSize(101);
        assertThat(team.currentNetRevenue()).isEqualByComparingTo("102.01");
        assertThat(team.currentAdditionalRevenue()).isEqualByComparingTo("20.20");
        assertThat(window.totalCount()).isEqualTo(101);
        assertThat(window.displayedCount()).isEqualTo(100);
        assertThat(window.hiddenCount()).isEqualTo(1);
        assertThat(window.hiddenCurrentNetRevenue()).isEqualByComparingTo("1.01");
        assertThat(window.hiddenPreviousNetRevenue()).isEqualByComparingTo("0.50");
        assertThat(window.hiddenCurrentAdditionalRevenue()).isEqualByComparingTo("0.20");
        assertThat(window.hiddenPreviousAdditionalRevenue()).isEqualByComparingTo("0.10");
    }

    @Test
    void perEmployeeMismatchFailsEvenWhenAggregateSellerTotalWouldMatch() {
        UUID first = new UUID(0, 1);
        UUID second = new UUID(0, 2);
        SellerCohortSnapshot cohort = new SellerCohortSnapshot(STORE, List.of(first, second));
        SellerPeriodFacts current = period(cohort,
                List.of(employee(first, "10", 1), employee(second, "20", 1)),
                List.of(sale(first, "20"), sale(second, "10")),
                List.of(additional(first, "0"), additional(second, "0")), "30", "0");
        SellerPeriodFacts previous = zeroPeriod(cohort);

        assertThatThrownBy(() -> projector.project(new SellerPeriodComparisonFacts(current, previous)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("employee/document revenue reconciliation");
    }

    @Test
    void returnOnlyEmployeeKeepsSignedContributionAndNoCompletedSales() {
        UUID id = new UUID(0, 3);
        SellerCohortSnapshot cohort = new SellerCohortSnapshot(STORE, List.of(id));
        SellerPeriodFacts current = period(cohort, List.of(employee(id, "-20", 1)),
                List.of(new SellerDocumentAggregate(id, BigDecimal.ZERO,
                        new BigDecimal("20"), 0, 1, 0)),
                List.of(additional(id, "-5")), "-20", "-5");

        var team = projector.project(new SellerPeriodComparisonFacts(current, zeroPeriod(cohort)));
        var contribution = team.employees().getFirst();

        assertThat(contribution.current().netRevenue()).isEqualByComparingTo("-20");
        assertThat(contribution.current().additionalRevenue()).isEqualByComparingTo("-5");
        assertThat(contribution.current().completedSaleCount()).isZero();
        assertThat(contribution.hasFinancialActivity()).isTrue();
    }

    @Test
    void allExcludedSaleCountsAsSaleDocumentButNotCompletedSample() {
        UUID id = new UUID(0, 4);
        SellerCohortSnapshot cohort = new SellerCohortSnapshot(STORE, List.of(id));
        SellerPeriodFacts current = period(cohort, List.of(employee(id, "0", 0)),
                List.of(new SellerDocumentAggregate(id, BigDecimal.ZERO,
                        BigDecimal.ZERO, 1, 0, 0)), List.of(additional(id, "0")), "0", "0");

        var team = projector.project(new SellerPeriodComparisonFacts(current, zeroPeriod(cohort)));

        assertThat(team.employees().getFirst().current().saleDocumentCount()).isEqualTo(1);
        assertThat(team.employees().getFirst().current().completedSaleCount()).isZero();
        assertThat(team.financiallyActiveIds()).containsExactly(id);
    }

    @Test
    void displayOrderCannotSilentlyDropAnActiveSeller() {
        UUID id = new UUID(0, 5);
        SellerCohortSnapshot cohort = new SellerCohortSnapshot(STORE, List.of(id));
        SellerPeriodFacts current = period(cohort, List.of(employee(id, "10", 1)),
                List.of(sale(id, "10")), List.of(additional(id, "0")), "10", "0");
        var team = projector.project(new SellerPeriodComparisonFacts(current, zeroPeriod(cohort)));

        assertThatThrownBy(() -> team.displayWindow(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("financially active seller");
    }

    @Test
    void provenEmptyCohortStaysEmptyWithoutStoreFallback() {
        SellerCohortSnapshot cohort = new SellerCohortSnapshot(STORE, List.of());
        var team = projector.project(new SellerPeriodComparisonFacts(
                zeroPeriod(cohort), zeroPeriod(cohort)));
        var window = team.displayWindow(List.of());

        assertThat(team.employees()).isEmpty();
        assertThat(team.currentNetRevenue()).isZero();
        assertThat(window.totalCount()).isZero();
        assertThat(window.hiddenCount()).isZero();
    }

    private SellerPeriodFacts zeroPeriod(SellerCohortSnapshot cohort) {
        return period(cohort, cohort.employeeIds().stream()
                .map(id -> employee(id, "0", 0)).toList(), List.of(),
                cohort.employeeIds().stream().map(id -> additional(id, "0")).toList(), "0", "0");
    }

    private SellerPeriodFacts period(
            SellerCohortSnapshot cohort,
            List<EmployeeKpiAggregate> employees,
            List<SellerDocumentAggregate> documents,
            List<EmployeeCategoryKpiAggregate> categories,
            String total,
            String additional
    ) {
        SellerPeriodFacts facts = mock(SellerPeriodFacts.class);
        SellerPeriodMetrics metrics = mock(SellerPeriodMetrics.class);
        CategoryKpiMetrics totals = mock(CategoryKpiMetrics.class);
        CategoryKpiMetrics additionalMetrics = mock(CategoryKpiMetrics.class);
        CategoryKpiResult categoryResult = mock(CategoryKpiResult.class);
        when(facts.metrics()).thenReturn(metrics);
        when(facts.documents()).thenReturn(documents);
        when(metrics.cohort()).thenReturn(cohort);
        when(metrics.employees()).thenReturn(employees);
        when(metrics.employeeCategories()).thenReturn(categories);
        when(metrics.totals()).thenReturn(totals);
        when(metrics.categories()).thenReturn(categoryResult);
        when(totals.netRevenue()).thenReturn(new BigDecimal(total));
        when(additionalMetrics.netRevenue()).thenReturn(new BigDecimal(additional));
        when(categoryResult.groups()).thenReturn(List.of(new CategoryKpiGroup(
                "ADDITIONAL_REVENUE", "Допы", additionalMetrics)));
        return facts;
    }

    private EmployeeKpiAggregate employee(UUID id, String revenue, long included) {
        return new EmployeeKpiAggregate(id, "Synthetic", true, true, true, true, false,
                new BigDecimal(revenue), BigDecimal.ONE, BigDecimal.ZERO,
                included, 0, 0, 0);
    }

    private SellerDocumentAggregate sale(UUID id, String revenue) {
        return new SellerDocumentAggregate(id, new BigDecimal(revenue), BigDecimal.ZERO,
                1, 0, 1);
    }

    private EmployeeCategoryKpiAggregate additional(UUID id, String amount) {
        return new EmployeeCategoryKpiAggregate(id, "Synthetic", true, true, true, true,
                false, "ACCESSORY", "Accessory", AnalyticsCategoryKind.ACCESSORY,
                DeviceFamily.NONE, true, false, false, true,
                new BigDecimal(amount), BigDecimal.ONE, BigDecimal.ZERO, 1, 0, 0);
    }
}
