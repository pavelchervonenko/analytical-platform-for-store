package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse.PeriodContext;
import com.storeanalytics.store.service.StoreDataStatusView;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class SellerWeeklyTemporalFenceTest {

    private static final String ZONE = "Europe/Kaliningrad";
    private static final Instant MONDAY = Instant.parse("2026-08-24T04:00:00Z");

    @Test
    void acceptsUnchangedLocalDateAndWeek() {
        SellerWeeklyReviewFacts facts = facts(MONDAY);

        assertThatCode(() -> SellerWeeklyTemporalFence.verify(facts, MONDAY.plusSeconds(60)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsLocalMidnightEvenWithoutDatabaseChanges() {
        SellerWeeklyReviewFacts facts = facts(MONDAY);

        assertThatThrownBy(() -> SellerWeeklyTemporalFence.verify(facts,
                Instant.parse("2026-08-24T22:00:00Z")))
                .isInstanceOf(SellerWeeklySourceChangedException.class);
    }

    @Test
    void rejectsNewReportingWeekEvenWhenFreshnessDateWasPrecomputedForIt() {
        SellerWeeklyReviewFacts facts = facts(MONDAY);
        StoreDataStatusView status = facts.sourceDataStatus();
        when(status.expectedThroughDate()).thenReturn(LocalDate.of(2026, 8, 30));

        assertThatThrownBy(() -> SellerWeeklyTemporalFence.verify(facts,
                Instant.parse("2026-08-31T04:00:00Z")))
                .isInstanceOf(SellerWeeklySourceChangedException.class);
    }

    private SellerWeeklyReviewFacts facts(Instant at) {
        SellerWeeklyReviewFacts facts = mock(SellerWeeklyReviewFacts.class);
        PeriodContext period = new WeeklyReviewPolicyV1().period(at, ZONE);
        StoreDataStatusView status = mock(StoreDataStatusView.class);
        when(facts.period()).thenReturn(period);
        when(facts.sourceDataStatus()).thenReturn(status);
        when(status.expectedThroughDate()).thenReturn(LocalDate.of(2026, 8, 23));
        return facts;
    }
}
