package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse.DateRange;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.PeriodContext;
import com.storeanalytics.metrics.service.SellerCohortSnapshot;
import com.storeanalytics.metrics.service.SellerPeriodComparisonFacts;
import com.storeanalytics.metrics.service.SellerPeriodFacts;
import com.storeanalytics.metrics.service.SellerPeriodMetrics;
import com.storeanalytics.store.service.StoreDataFreshnessStatus;
import com.storeanalytics.store.service.StoreDataStatusView;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SellerWeeklySourceIdentityTest {

    private final SellerWeeklySourceIdentity identity = new SellerWeeklySourceIdentity();

    @Test
    void identityChangesWithRevisionButNotPresentationOnlyFields() {
        UUID storeId = UUID.randomUUID();
        SellerWeeklyReviewFacts facts = mock(SellerWeeklyReviewFacts.class);
        SellerPeriodComparisonFacts comparison = mock(SellerPeriodComparisonFacts.class);
        SellerPeriodFacts current = mock(SellerPeriodFacts.class);
        SellerPeriodFacts previous = mock(SellerPeriodFacts.class);
        SellerPeriodMetrics metrics = mock(SellerPeriodMetrics.class);
        StoreDataStatusView status = mock(StoreDataStatusView.class);
        when(facts.storeId()).thenReturn(storeId);
        when(facts.period()).thenReturn(new PeriodContext("Europe/Kaliningrad",
                new DateRange(LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 23)),
                new DateRange(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 16)),
                "Current", "Previous"));
        when(facts.comparison()).thenReturn(comparison);
        when(comparison.current()).thenReturn(current);
        when(comparison.previous()).thenReturn(previous);
        when(current.metrics()).thenReturn(metrics);
        when(metrics.cohort()).thenReturn(new SellerCohortSnapshot(storeId, List.of(UUID.randomUUID())));
        when(current.attachFormulaVersion()).thenReturn("attach-rate-v4");
        when(previous.attachFormulaVersion()).thenReturn("attach-rate-v4");
        when(facts.sourceDataStatus()).thenReturn(status);
        when(status.status()).thenReturn(StoreDataFreshnessStatus.CURRENT);
        when(status.expectedThroughDate()).thenReturn(LocalDate.of(2026, 8, 23));
        when(status.dataThroughDate()).thenReturn(LocalDate.of(2026, 8, 23));
        when(facts.sourceStability()).thenReturn(SellerWeeklySourceStability.STABLE);
        when(facts.sourceCoverage()).thenReturn(SellerWeeklySourceCoverage.complete());
        when(facts.sourceRevision()).thenReturn(1L);

        String original = identity.hash(facts);
        assertThat(identity.hash(SellerWeeklyIdentityFacts.from(facts))).isEqualTo(original);
        when(status.lastError()).thenReturn("Presentation only");
        assertThat(identity.hash(facts)).isEqualTo(original);
        when(facts.sourceRevision()).thenReturn(2L);
        assertThat(identity.hash(facts)).isNotEqualTo(original);
        assertThat(original).matches("[a-f0-9]{64}");
    }

    @Test
    void lightweightProjectionPreservesOriginalCanonicalBytes() {
        UUID storeId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        var period = new PeriodContext("Europe/Kaliningrad",
                new DateRange(LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 23)),
                new DateRange(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 16)), "Current", "Previous");
        StoreDataStatusView status = mock(StoreDataStatusView.class);
        when(status.status()).thenReturn(StoreDataFreshnessStatus.CURRENT);
        when(status.expectedThroughDate()).thenReturn(LocalDate.of(2026, 8, 23));
        when(status.dataThroughDate()).thenReturn(LocalDate.of(2026, 8, 22));
        when(status.openQualityIssueCount()).thenReturn(3L);
        var coverage = new SellerWeeklySourceCoverage(new SellerWeeklySourceCoverage.Window(true, true),
                new SellerWeeklySourceCoverage.Window(true, false),
                new SellerWeeklySourceCoverage.Window(false, true));
        var facts = new SellerWeeklyIdentityFacts(storeId, period, status, "b".repeat(64),
                "attach-rate-v4", "attach-rate-v4", SellerWeeklySourceStability.STABLE, coverage, 7L);

        assertThat(identity.hash(facts))
                .isEqualTo("582b58025bca7aea0a26bc933345947a14d01d03d48e3cca335f19218bae7100");
    }
}
