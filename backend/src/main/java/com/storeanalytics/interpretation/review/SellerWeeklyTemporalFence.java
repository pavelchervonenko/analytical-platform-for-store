package com.storeanalytics.interpretation.review;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse.PeriodContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Rejects facts read before a local-day or reporting-week boundary. */
final class SellerWeeklyTemporalFence {

    private static final WeeklyReviewPolicyV1 PERIOD_POLICY = new WeeklyReviewPolicyV1();

    private SellerWeeklyTemporalFence() {
    }

    static void verify(SellerWeeklyReviewFacts facts, Instant writeTime) {
        verify(facts.period(), facts.sourceDataStatus().expectedThroughDate(), writeTime);
    }

    static void verify(SellerWeeklyIdentityFacts facts, Instant writeTime) {
        verify(facts.period(), facts.sourceDataStatus().expectedThroughDate(), writeTime);
    }

    private static void verify(PeriodContext period, LocalDate factsExpectedThroughDate, Instant writeTime) {
        String timezone = period.timezone();
        LocalDate expectedThroughDate = LocalDate.ofInstant(writeTime, ZoneId.of(timezone))
                .minusDays(1);
        var currentPeriod = PERIOD_POLICY.period(writeTime, timezone);
        if (!expectedThroughDate.equals(factsExpectedThroughDate)
                || !currentPeriod.current().equals(period.current())
                || !currentPeriod.previous().equals(period.previous())) {
            throw new SellerWeeklySourceChangedException();
        }
    }
}
