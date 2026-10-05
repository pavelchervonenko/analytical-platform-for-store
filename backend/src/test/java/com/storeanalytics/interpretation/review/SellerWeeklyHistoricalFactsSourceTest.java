package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeanalytics.metrics.service.SellerHistoricalComparisonFacts;
import com.storeanalytics.metrics.service.SellerHistoricalFactsService;
import com.storeanalytics.metrics.service.SellerHistoricalFactsUnavailableException;
import com.storeanalytics.metrics.service.SellerPeriodComparisonFacts;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import com.storeanalytics.store.service.StoreDataStatusService;
import com.storeanalytics.store.service.StoreDataStatusView;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

class SellerWeeklyHistoricalFactsSourceTest {
    private static final UUID STORE = UUID.randomUUID();
    private static final LocalDate START = LocalDate.parse("2026-09-14");
    private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");
    private static final String ZONE = "Europe/Kaliningrad";
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SellerHistoricalFactsService sellers = mock(SellerHistoricalFactsService.class);
    private final StoreDataStatusService status = mock(StoreDataStatusService.class);
    private final SellerWeeklySourceStabilityRepository stability = mock(SellerWeeklySourceStabilityRepository.class);
    private final SellerWeeklySourceCoverageRepository coverage = mock(SellerWeeklySourceCoverageRepository.class);
    private final SellerWeeklySourceRevisionRepository revision = mock(SellerWeeklySourceRevisionRepository.class);
    private final SellerWeeklyHistoricalFactsSource source = new SellerWeeklyHistoricalFactsSource(
            jdbc, sellers, status, stability, coverage, revision);

    @BeforeEach
    void configure() {
        when(jdbc.queryForList("SELECT timezone FROM stores WHERE id = ? AND is_active", String.class, STORE))
                .thenReturn(List.of(ZONE));
        when(coverage.read(eq(STORE), any())).thenReturn(SellerWeeklySourceCoverage.complete());
        when(stability.read(eq(STORE), any(), any())).thenReturn(SellerWeeklySourceStability.STABLE);
        when(revision.read(STORE)).thenReturn(42L);
    }

    @Test
    void readsExactOldWeekAfterMultipleBoundariesWithoutCurrentWeekSubstitution() {
        var comparison = mock(SellerPeriodComparisonFacts.class, RETURNS_DEEP_STUBS);
        var current = new StoreKpiPeriod(START, START.plusDays(6));
        var previous = new StoreKpiPeriod(START.minusWeeks(1), START.minusDays(1));
        when(comparison.current().metrics().cohort().storeId()).thenReturn(STORE);
        when(comparison.current().metrics().cohort().employeeIds()).thenReturn(List.of());
        when(comparison.previous().metrics().cohort().storeId()).thenReturn(STORE);
        when(comparison.current().metrics().period()).thenReturn(current);
        when(comparison.previous().metrics().period()).thenReturn(previous);
        var historical = new SellerHistoricalComparisonFacts(comparison, Set.of());
        when(sellers.read(eq(STORE), eq(current), eq(previous), eq(ZoneId.of(ZONE)), eq(NOW)))
                .thenReturn(historical);
        var view = mock(StoreDataStatusView.class);
        when(view.storeId()).thenReturn(STORE);
        when(status.get(STORE)).thenReturn(view);
        var facts = source.load(STORE, START, ZONE, NOW);
        assertThat(facts.period().current().start()).isEqualTo(START);
        assertThat(facts.historical()).isSameAs(historical);
        assertThat(facts.sourceRevision()).isEqualTo(42);
        verify(stability).read(STORE, Instant.parse("2026-09-06T22:00:00Z"),
                Instant.parse("2026-09-20T22:00:00Z"));
    }

    @Test
    void incompleteComparisonCoverageDefersBeforeHeavyFacts() {
        when(coverage.read(eq(STORE), any())).thenReturn(new SellerWeeklySourceCoverage(
                new SellerWeeklySourceCoverage.Window(true, false),
                new SellerWeeklySourceCoverage.Window(true, true),
                new SellerWeeklySourceCoverage.Window(true, true)));
        assertUnavailable("SOURCE_COVERAGE_INCOMPLETE");
        verifyNoInteractions(sellers, status, revision, stability);
    }

    @Test
    void activeSourceWritesAndUnreconciledFailureRemainSeparateDeferrals() {
        when(stability.read(eq(STORE), any(), any())).thenReturn(SellerWeeklySourceStability.IN_PROGRESS);
        assertUnavailable("SOURCE_WRITES_ACTIVE");
        when(stability.read(eq(STORE), any(), any())).thenReturn(SellerWeeklySourceStability.NEEDS_RECONCILIATION);
        assertUnavailable("SOURCE_RECONCILIATION_REQUIRED");
        verifyNoInteractions(sellers, status, revision);
    }

    @Test
    void missingStoreOrChangedZoneCannotReinterpretOldPeriod() {
        assertThatThrownBy(() -> source.load(STORE, START, "UTC", NOW))
                .hasMessage("STORE_CONFIGURATION_CHANGED");
        when(jdbc.queryForList("SELECT timezone FROM stores WHERE id = ? AND is_active", String.class, STORE))
                .thenReturn(List.of());
        assertUnavailable("STORE_CONFIGURATION_CHANGED");
        verifyNoInteractions(sellers, coverage, stability);
    }

    @Test
    void openWeekAndNonMondayAreRejectedBeforeCoverage() {
        assertThatThrownBy(() -> source.load(STORE, LocalDate.parse("2026-10-05"), ZONE, NOW))
                .hasMessage("WEEK_NOT_CLOSED");
        assertThatThrownBy(() -> source.load(STORE, START.plusDays(1), ZONE, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(sellers, coverage, stability);
    }

    @Test
    void unknownMembershipPropagatesWithoutCurrentRosterFallback() {
        when(sellers.read(eq(STORE), any(), any(), any(), any()))
                .thenThrow(new SellerHistoricalFactsUnavailableException("DOCUMENT_MEMBERSHIP_OR_AUTHOR_UNKNOWN"));
        assertUnavailable("DOCUMENT_MEMBERSHIP_OR_AUTHOR_UNKNOWN");
        verifyNoInteractions(status, revision);
    }

    @Test
    void sourceDeclaresOneReadOnlyRepeatableReadTransaction() throws Exception {
        var annotation = SellerWeeklyHistoricalFactsSource.class
                .getMethod("load", UUID.class, LocalDate.class, String.class, Instant.class)
                .getAnnotation(Transactional.class);
        assertThat(annotation.readOnly()).isTrue();
        assertThat(annotation.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    }

    private void assertUnavailable(String reason) {
        assertThatThrownBy(() -> source.load(STORE, START, ZONE, NOW))
                .isInstanceOf(SellerHistoricalFactsUnavailableException.class).hasMessage(reason);
    }
}
