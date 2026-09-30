package com.storeanalytics.metrics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.storeanalytics.metrics.exception.StoreNotFoundException;
import com.storeanalytics.metrics.repository.EmployeeCategoryKpiAggregate;
import com.storeanalytics.metrics.repository.EmployeeCategoryKpiRepository;
import com.storeanalytics.metrics.repository.EmployeeKpiAggregate;
import com.storeanalytics.metrics.repository.EmployeeKpiRepository;
import com.storeanalytics.metrics.repository.SellerAttachRateRepository;
import com.storeanalytics.metrics.repository.AttachRateAggregate;
import com.storeanalytics.metrics.repository.SellerCohortRepository;
import com.storeanalytics.metrics.repository.SellerDocumentAggregate;
import com.storeanalytics.metrics.repository.SellerDocumentRepository;
import com.storeanalytics.metrics.repository.SellerReturnAttributionRepository;
import com.storeanalytics.product.model.AnalyticsCategoryKind;
import com.storeanalytics.product.model.AttachDenominatorCode;
import com.storeanalytics.product.model.DeviceFamily;
import com.storeanalytics.store.repository.StoreRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SellerPeriodAnalyticsServiceTest {

    private static final UUID STORE = UUID.randomUUID();
    private static final UUID SELLER = UUID.randomUUID();
    private static final UUID EXCLUDED = UUID.randomUUID();
    private static final StoreKpiPeriod CURRENT = new StoreKpiPeriod(
            LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 13));
    private static final StoreKpiPeriod PREVIOUS = new StoreKpiPeriod(
            LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 6));

    private final StoreRepository stores = mock(StoreRepository.class);
    private final SellerCohortRepository cohorts = mock(SellerCohortRepository.class);
    private final EmployeeKpiRepository employees = mock(EmployeeKpiRepository.class);
    private final EmployeeCategoryKpiRepository categories = mock(EmployeeCategoryKpiRepository.class);
    private final SellerDocumentRepository documents = mock(SellerDocumentRepository.class);
    private final SellerAttachRateRepository attach = mock(SellerAttachRateRepository.class);
    private final SellerReturnAttributionRepository returnAttribution =
            mock(SellerReturnAttributionRepository.class);
    private final SellerCohortSnapshot cohort = new SellerCohortSnapshot(STORE, List.of(SELLER));
    private final SellerPeriodAnalyticsService service = new SellerPeriodAnalyticsService(
            stores, cohorts, employees, categories, documents, attach, returnAttribution);

    @BeforeEach
    void setUp() {
        when(stores.existsById(STORE)).thenReturn(true);
        when(cohorts.read(STORE)).thenReturn(cohort);
        when(attach.formulaVersion()).thenReturn("attach-rate-v3");
        when(returnAttribution.read(STORE, CURRENT)).thenReturn(SellerReturnAttributionQuality.COMPLETE);
        when(returnAttribution.read(STORE, PREVIOUS)).thenReturn(SellerReturnAttributionQuality.COMPLETE);
        stub(CURRENT, "100", "60", 0);
        stub(PREVIOUS, "80", "40", 0);
    }

    @Test
    void lightweightMetricsKeepSellerCostIndependentOfExcludedMissingCost() {
        SellerPeriodMetrics result = service.readMetrics(STORE, CURRENT);

        assertThat(result.totals().netRevenue()).isEqualByComparingTo("100.00");
        assertThat(result.totals().costAmount()).isEqualByComparingTo("60.00");
        assertThat(result.totals().grossProfit()).isEqualByComparingTo("40.00");
        assertThat(result.totals().dataQuality().missingCostItemCount()).isZero();
        assertThat(result.employees()).extracting(EmployeeKpiAggregate::employeeId).containsExactly(SELLER);
        assertThat(result.categories().groups()).filteredOn(group -> "DEVICES".equals(group.groupCode()))
                .singleElement().satisfies(group ->
                        assertThat(group.metrics().netRevenue()).isEqualByComparingTo("100"));
        verifyNoInteractions(documents, attach, returnAttribution);
    }

    @Test
    void overviewReusesEachFullProjectionOnceWithoutWeeklyReaders() {
        SellerOverviewMetrics result = service.readForOverview(STORE, CURRENT);

        assertThat(result.sellers()).isEqualTo(service.readMetrics(STORE, CURRENT));
        assertThat(result.reconciliationEmployees().employees()).hasSize(3);
        assertThat(result.reconciliationCategories().employees()).hasSize(3);
        // One read for the Overview bundle, one for the independent comparison above.
        verify(cohorts, times(2)).read(STORE);
        verify(employees, times(2)).aggregate(STORE, CURRENT.start(), CURRENT.end());
        verify(categories, times(2)).aggregate(STORE, CURRENT.start(), CURRENT.end());
        verifyNoMoreInteractions(cohorts, employees, categories);
        verifyNoInteractions(documents, attach, returnAttribution);
    }

    @Test
    void emptyOverviewRosterRetainsStoreReconciliationWithoutLeakingStoreTotals() {
        when(cohorts.read(STORE)).thenReturn(new SellerCohortSnapshot(STORE, List.of()));

        SellerOverviewMetrics result = service.readForOverview(STORE, CURRENT);

        assertThat(result.sellers().totals().netRevenue()).isZero();
        assertThat(result.sellers().employees()).isEmpty();
        assertThat(result.reconciliationEmployees().employees()).hasSize(3);
        verify(employees).aggregate(STORE, CURRENT.start(), CURRENT.end());
        verify(categories).aggregate(STORE, CURRENT.start(), CURRENT.end());
        verifyNoInteractions(documents, attach, returnAttribution);
    }

    @Test
    void bothPeriodsUseOneFrozenRosterAndBoundedReads() {
        SellerPeriodComparisonFacts result = service.readComparison(STORE, CURRENT, PREVIOUS);

        assertThat(result.current().metrics().cohort()).isSameAs(result.previous().metrics().cohort());
        assertThat(result.previous().metrics().totals().netRevenue()).isEqualByComparingTo("80");
        verify(cohorts, times(1)).read(STORE);
        verify(employees).aggregate(STORE, CURRENT.start(), CURRENT.end());
        verify(employees).aggregate(STORE, PREVIOUS.start(), PREVIOUS.end());
        verify(categories).aggregate(STORE, CURRENT.start(), CURRENT.end());
        verify(categories).aggregate(STORE, PREVIOUS.start(), PREVIOUS.end());
        verify(documents).read(cohort, CURRENT);
        verify(documents).read(cohort, PREVIOUS);
        verify(attach).read(cohort, CURRENT);
        verify(attach).read(cohort, PREVIOUS);
        verify(attach, times(2)).formulaVersion();
        verify(returnAttribution).read(STORE, CURRENT);
        verify(returnAttribution).read(STORE, PREVIOUS);
        verifyNoMoreInteractions(cohorts, employees, categories, documents, attach, returnAttribution);
    }

    @Test
    void sellerAttachProjectionUsesTheSelectedV4FormulaAndExistingClamp() {
        when(attach.formulaVersion()).thenReturn("attach-rate-v4");
        when(attach.read(cohort, CURRENT)).thenReturn(List.of(new AttachRateAggregate(
                "CASE_TO_PHONE", "CASE", AttachDenominatorCode.PHONE,
                new BigDecimal("-1.000"), new BigDecimal("5.000"),
                0, 3, 0, false, 2, 1)));

        AttachRateResult result = service.readComparison(STORE, CURRENT, PREVIOUS)
                .current().projectedAttachRates();

        assertThat(result.formulaVersion()).isEqualTo("attach-rate-v4");
        assertThat(result.rates()).singleElement().satisfies(rate -> {
            assertThat(rate.numeratorReceiptCount()).isEqualByComparingTo("-1.000");
            assertThat(rate.ratePerHundred()).isEqualByComparingTo("0.00");
        });
        assertThat(result.dataQuality().unassignedReturnItemCount()).isEqualTo(2);
    }

    @Test
    void emptyRosterNeverFallsBackToStoreFacts() {
        when(cohorts.read(STORE)).thenReturn(new SellerCohortSnapshot(STORE, List.of()));

        SellerPeriodMetrics result = service.readMetrics(STORE, CURRENT);

        assertThat(result.employees()).isEmpty();
        assertThat(result.totals().netRevenue()).isEqualByComparingTo("0");
        assertThat(result.totals().marginPercent()).isNull();
        assertThat(result.categories().groups()).hasSize(5);
        verifyNoInteractions(employees, categories, documents, attach, returnAttribution);
    }

    @Test
    void missingCostMasksOnlyDependentMetricsAndZeroCostRemainsValid() {
        stub(CURRENT, "100", "0", 1);
        SellerPeriodMetrics missing = service.readMetrics(STORE, CURRENT);
        assertThat(missing.totals().netRevenue()).isEqualByComparingTo("100");
        assertThat(missing.totals().grossProfit()).isNull();

        stub(CURRENT, "100", "0", 0);
        SellerPeriodMetrics zero = service.readMetrics(STORE, CURRENT);
        assertThat(zero.totals().grossProfit()).isEqualByComparingTo("100");
        assertThat(zero.totals().dataQuality().completeCostData()).isTrue();
    }

    @Test
    void negativeRevenueKeepsExistingFormulaInsteadOfClamping() {
        stub(CURRENT, "-40", "-20", 0);
        SellerPeriodMetrics result = service.readMetrics(STORE, CURRENT);
        assertThat(result.totals().netRevenue()).isEqualByComparingTo("-40");
        assertThat(result.totals().grossProfit()).isEqualByComparingTo("-20");
        assertThat(result.totals().marginPercent()).isEqualByComparingTo("50");
    }

    @Test
    void missingSelectedSellerAndDisagreeingProjectionsFailClosed() {
        when(employees.aggregate(STORE, CURRENT.start(), CURRENT.end())).thenReturn(List.of());
        assertThatThrownBy(() -> service.readMetrics(STORE, CURRENT))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("cover");
        stub(CURRENT, "100", "60", 0);
        when(categories.aggregate(STORE, CURRENT.start(), CURRENT.end()))
                .thenReturn(List.of(category(SELLER, "99", "60", 0)));
        assertThatThrownBy(() -> service.readMetrics(STORE, CURRENT))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("reconciliation");
    }

    @Test
    void documentRevenueMustReconcileBeforeComparisonCanBeReturned() {
        when(documents.read(cohort, CURRENT)).thenReturn(List.of());
        assertThatThrownBy(() -> service.readComparison(STORE, CURRENT, PREVIOUS))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("document revenue");
    }

    @Test
    void unknownStoreDoesNotReadAnyFacts() {
        when(stores.existsById(STORE)).thenReturn(false);
        assertThatThrownBy(() -> service.readMetrics(STORE, CURRENT))
                .isInstanceOf(StoreNotFoundException.class);
        verifyNoInteractions(cohorts, employees, categories, documents, attach, returnAttribution);
    }

    @Test
    void displayLimitDoesNotTruncateFinancialOrCategoryFacts() {
        List<UUID> ids = java.util.stream.IntStream.range(0, 101)
                .mapToObj(index -> new UUID(0, index + 1)).toList();
        when(cohorts.read(STORE)).thenReturn(new SellerCohortSnapshot(STORE, ids));
        when(employees.aggregate(STORE, CURRENT.start(), CURRENT.end())).thenReturn(ids.stream()
                .map(id -> employee(id, "1.01", "0", 0)).toList());
        when(categories.aggregate(STORE, CURRENT.start(), CURRENT.end())).thenReturn(ids.stream()
                .map(id -> category(id, "1.01", "0", 0)).toList());

        SellerPeriodMetrics result = service.readMetrics(STORE, CURRENT);

        assertThat(result.employees()).hasSize(101);
        assertThat(result.totals().netRevenue()).isEqualByComparingTo("102.01");
        verifyNoInteractions(documents, attach, returnAttribution);
    }

    @Test
    void cohortIdentityIsImmutableOrderIndependentAndStoreScoped() {
        List<UUID> mutable = new ArrayList<>(List.of(SELLER, EXCLUDED, SELLER));
        SellerCohortSnapshot first = new SellerCohortSnapshot(STORE, mutable);
        mutable.clear();
        SellerCohortSnapshot second = new SellerCohortSnapshot(STORE, List.of(EXCLUDED, SELLER));
        assertThat(first).isEqualTo(second);
        assertThat(first.fingerprint()).isEqualTo(second.fingerprint()).hasSize(64);
        assertThat(first.fingerprint()).isNotEqualTo(cohort.fingerprint());
        assertThat(first.fingerprint()).isNotEqualTo(
                new SellerCohortSnapshot(UUID.randomUUID(), first.employeeIds()).fingerprint());
        assertThatThrownBy(() -> first.employeeIds().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private void stub(StoreKpiPeriod period, String revenue, String cost, long missing) {
        when(employees.aggregate(STORE, period.start(), period.end())).thenReturn(List.of(
                employee(SELLER, revenue, cost, missing), employee(EXCLUDED, "900", "0", 1),
                employee(null, "30", "0", 1)));
        when(categories.aggregate(STORE, period.start(), period.end())).thenReturn(List.of(
                category(SELLER, revenue, cost, missing), category(EXCLUDED, "900", "0", 1),
                category(null, "30", "0", 1)));
        when(documents.read(cohort, period)).thenReturn(List.of(new SellerDocumentAggregate(
                SELLER, new BigDecimal(revenue), BigDecimal.ZERO, 1, 0, 1)));
    }

    private EmployeeKpiAggregate employee(UUID id, String revenue, String cost, long missing) {
        // Flags are deliberately false: readers must use the frozen cohort, not reselect it.
        return new EmployeeKpiAggregate(id, "Synthetic", false, true, false, false, id == null,
                new BigDecimal(revenue), BigDecimal.ONE, new BigDecimal(cost), 1, 0, missing, 0);
    }

    private EmployeeCategoryKpiAggregate category(UUID id, String revenue, String cost, long missing) {
        return new EmployeeCategoryKpiAggregate(id, "Synthetic", false, true, false, false, id == null,
                "IPHONE_NEW_ASIS", "Synthetic phone", AnalyticsCategoryKind.DEVICE, DeviceFamily.IPHONE,
                true, true, true, false, new BigDecimal(revenue), BigDecimal.ONE, new BigDecimal(cost),
                1, missing, 0);
    }
}
