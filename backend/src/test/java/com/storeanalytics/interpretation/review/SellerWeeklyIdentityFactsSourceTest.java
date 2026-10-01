package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.storeanalytics.metrics.repository.SellerAttachRateRepository;
import com.storeanalytics.metrics.repository.SellerCohortRepository;
import com.storeanalytics.metrics.service.SellerCohortSnapshot;
import com.storeanalytics.store.service.StoreDataFreshnessStatus;
import com.storeanalytics.store.service.StoreDataStatusService;
import com.storeanalytics.store.service.StoreDataStatusView;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

class SellerWeeklyIdentityFactsSourceTest {

    private static final Instant NOW = Instant.parse("2026-08-24T04:00:00Z");
    private static final String ZONE = "Europe/Kaliningrad";
    private final SellerCohortRepository cohorts = mock(SellerCohortRepository.class);
    private final SellerAttachRateRepository attach = mock(SellerAttachRateRepository.class);
    private final StoreDataStatusService status = mock(StoreDataStatusService.class);
    private final SellerWeeklySourceStabilityRepository stability = mock(SellerWeeklySourceStabilityRepository.class);
    private final SellerWeeklySourceCoverageRepository coverage = mock(SellerWeeklySourceCoverageRepository.class);
    private final SellerWeeklySourceRevisionRepository revisions = mock(SellerWeeklySourceRevisionRepository.class);
    private final SellerWeeklyIdentityFactsSource source = new SellerWeeklyIdentityFactsSource(
            cohorts, attach, status, stability, coverage, revisions);

    @Test
    void readsAllCanonicalMetadataWithoutCalculatingAttachOrFinancialFacts() {
        UUID storeId = arrange();
        var period = new WeeklyReviewPolicyV1().period(NOW, ZONE);
        var cohort = new SellerCohortSnapshot(storeId, List.of(UUID.randomUUID(), UUID.randomUUID()));
        when(cohorts.read(storeId)).thenReturn(cohort);

        var facts = source.load(storeId, NOW, ZONE);

        assertThat(facts.storeId()).isEqualTo(storeId);
        assertThat(facts.period()).isEqualTo(period);
        assertThat(facts.sourceDataStatus()).isSameAs(status.get(storeId));
        assertThat(facts.cohortFingerprint()).isEqualTo(cohort.fingerprint());
        assertThat(facts.currentAttachFormulaVersion()).isEqualTo("attach-rate-v4");
        assertThat(facts.previousAttachFormulaVersion()).isEqualTo("attach-rate-v4");
        assertThat(facts.sourceStability()).isEqualTo(SellerWeeklySourceStability.STABLE);
        assertThat(facts.sourceCoverage()).isEqualTo(SellerWeeklySourceCoverage.complete());
        assertThat(facts.sourceRevision()).isEqualTo(4);
        verify(attach).formulaVersion();
        verifyNoMoreInteractions(attach);
        verify(stability).read(storeId, period.previous().start().atStartOfDay(ZoneId.of(ZONE)).toInstant(),
                period.current().end().plusDays(1).atStartOfDay(ZoneId.of(ZONE)).toInstant());
    }

    @Test
    void runtimeAttachVersionChangesIdentityEvenWhenDatabaseRevisionIsUnchanged() {
        UUID storeId = arrange();
        var identity = new SellerWeeklySourceIdentity();
        String original = identity.hash(source.load(storeId, NOW, ZONE));
        when(attach.formulaVersion()).thenReturn("attach-rate-v3");

        assertThat(identity.hash(source.load(storeId, NOW, ZONE))).isNotEqualTo(original);
    }

    @Test
    void emptyCurrentRosterHasCanonicalIdentityAndTransactionIsRepeatableRead() throws Exception {
        UUID storeId = arrange();
        var facts = source.load(storeId, NOW, ZONE);
        assertThat(facts.cohortFingerprint()).isEqualTo(new SellerCohortSnapshot(storeId, List.of()).fingerprint());
        Transactional transaction = SellerWeeklyIdentityFactsSource.class
                .getMethod("load", UUID.class, Instant.class, String.class).getAnnotation(Transactional.class);
        assertThat(transaction.readOnly()).isTrue();
        assertThat(transaction.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    }

    private UUID arrange() {
        UUID storeId = UUID.randomUUID();
        var period = new WeeklyReviewPolicyV1().period(NOW, ZONE);
        StoreDataStatusView view = mock(StoreDataStatusView.class);
        when(view.status()).thenReturn(StoreDataFreshnessStatus.CURRENT);
        when(view.expectedThroughDate()).thenReturn(LocalDate.of(2026, 8, 23));
        when(view.dataThroughDate()).thenReturn(LocalDate.of(2026, 8, 23));
        when(status.get(storeId)).thenReturn(view);
        when(cohorts.read(storeId)).thenReturn(new SellerCohortSnapshot(storeId, List.of()));
        when(attach.formulaVersion()).thenReturn("attach-rate-v4");
        when(stability.read(storeId, period.previous().start().atStartOfDay(ZoneId.of(ZONE)).toInstant(),
                period.current().end().plusDays(1).atStartOfDay(ZoneId.of(ZONE)).toInstant()))
                .thenReturn(SellerWeeklySourceStability.STABLE);
        when(coverage.read(storeId, period)).thenReturn(SellerWeeklySourceCoverage.complete());
        when(revisions.read(storeId)).thenReturn(4L);
        return storeId;
    }
}
