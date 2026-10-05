package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeanalytics.integration.llm.yandex.YandexLlmProperties;
import com.storeanalytics.interpretation.review.SellerWeeklyAutomaticAiCandidates.Candidate;
import com.storeanalytics.interpretation.review.ai.WeeklyReviewAiJobStore;
import com.storeanalytics.interpretation.review.ai.WeeklyReviewAiTestProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SellerWeeklyHistoricalAiPlanningServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");
    private final SellerWeeklyAutomaticAiCandidates candidates = mock(SellerWeeklyAutomaticAiCandidates.class);
    private final SellerWeeklyHistoricalReviewService reviews = mock(SellerWeeklyHistoricalReviewService.class);
    private final WeeklyReviewAiJobStore jobs = mock(WeeklyReviewAiJobStore.class);
    private final YandexLlmProperties yandex = mock(YandexLlmProperties.class);
    private final SellerWeeklyPreparationProperties preparation = new SellerWeeklyPreparationProperties(
            true, Duration.ofMinutes(1), 10, 4, 25, 2, Duration.ofMinutes(1));

    @Test
    void noOptInOrNoPaidParentMeansNoDatabaseOrJobActivity() {
        for (var ai : List.of(WeeklyReviewAiTestProperties.properties(false, true, false),
                WeeklyReviewAiTestProperties.properties(true, false, true))) {
            var planner = new SellerWeeklyHistoricalAiPlanningService(candidates, reviews, jobs, preparation,
                    ai, yandex, Clock.fixed(NOW, ZoneOffset.UTC));
            assertThat(planner.plan()).isZero();
        }
        var disabled = new SellerWeeklyPreparationProperties(false, Duration.ofMinutes(1),
                10, 4, 25, 2, Duration.ofMinutes(1));
        assertThat(new SellerWeeklyHistoricalAiPlanningService(candidates, reviews, jobs, disabled,
                WeeklyReviewAiTestProperties.properties(true, true, true), yandex,
                Clock.fixed(NOW, ZoneOffset.UTC)).plan()).isZero();
        verifyNoInteractions(candidates, reviews, jobs, yandex);
    }

    @Test
    void failedOlderPeriodDoesNotStarveOtherClosedHistoricalWeeks() {
        var first = candidate("2026-09-14");
        var second = candidate("2026-09-21");
        var third = candidate("2026-09-28");
        when(candidates.readyAfter(null, 10, NOW)).thenReturn(List.of(first, second, third));
        when(reviews.period(first.storeId(), first.periodStart())).thenThrow(new IllegalStateException("Synthetic"));
        ready(second);
        ready(third);
        assertThat(planner().plan()).isEqualTo(2);
        verify(reviews).period(second.storeId(), second.periodStart());
        verify(reviews).period(third.storeId(), third.periodStart());
        verify(reviews, never()).refresh(any(), any(), any());
    }

    @Test
    void preparingStaleOrLegacyRosterPeriodNeverEnqueuesAndDoesNotRefresh() {
        var first = candidate("2026-09-14");
        var second = candidate("2026-09-21");
        var third = candidate("2026-09-28");
        when(candidates.readyAfter(null, 10, NOW)).thenReturn(List.of(first, second, third));
        when(reviews.period(first.storeId(), first.periodStart())).thenReturn(new SellerWeeklyReviewView(
                SellerWeeklyReviewView.Freshness.PREPARING, null));
        var stale = report(second);
        when(reviews.period(second.storeId(), second.periodStart())).thenReturn(new SellerWeeklyReviewView(
                SellerWeeklyReviewView.Freshness.STALE, stale));
        var legacy = report(third);
        when(legacy.membership()).thenReturn(new WeeklyReviewV3Response.Membership(
                "CURRENT_RANKING_AT_GENERATION", "a".repeat(64), "a".repeat(64), "b".repeat(64), NOW, 1));
        when(reviews.period(third.storeId(), third.periodStart())).thenReturn(new SellerWeeklyReviewView(
                SellerWeeklyReviewView.Freshness.CURRENT, legacy));
        assertThat(planner().plan()).isZero();
        verifyNoInteractions(jobs);
        verify(reviews, never()).refresh(any(), any(), any());
    }

    @Test
    void anotherRevisionOrBlockedReportCannotReplaceTheExactPreparedBinding() {
        var first = candidate("2026-09-14");
        var second = candidate("2026-09-21");
        when(candidates.readyAfter(null, 10, NOW)).thenReturn(List.of(first, second));
        var changed = report(first);
        when(changed.provenance()).thenReturn(new WeeklyReviewResponse.Provenance(
                UUID.randomUUID().toString(), 2, NOW, NOW, false, null));
        when(reviews.period(first.storeId(), first.periodStart())).thenReturn(new SellerWeeklyReviewView(
                SellerWeeklyReviewView.Freshness.CURRENT, changed));
        var blocked = report(second);
        when(blocked.reportState()).thenReturn(WeeklyReviewResponse.ReportState.BLOCKED);
        when(reviews.period(second.storeId(), second.periodStart())).thenReturn(new SellerWeeklyReviewView(
                SellerWeeklyReviewView.Freshness.CURRENT, blocked));
        assertThat(planner().plan()).isZero();
        verifyNoInteractions(jobs);
    }

    @Test
    void timeBudgetResumesAfterLastProcessedPeriodRatherThanRepeatingItsPage() {
        var first = candidate("2026-09-14");
        var second = candidate("2026-09-21");
        when(candidates.readyAfter(null, 10, NOW)).thenReturn(List.of(first, second));
        when(candidates.readyAfter(first, 10, NOW)).thenReturn(List.of(second));
        ready(first);
        ready(second);
        var calls = new AtomicInteger();
        var planner = new SellerWeeklyHistoricalAiPlanningService(candidates, reviews, jobs, preparation,
                WeeklyReviewAiTestProperties.properties(true, true, true), yandex,
                new SellerWeeklyHistoricalAiPlanningService.Timing(Clock.fixed(NOW, ZoneOffset.UTC),
                        () -> calls.getAndIncrement() <= 1 ? 0 : Duration.ofMinutes(2).toNanos()));
        assertThat(planner.plan()).isOne();
        assertThat(planner.plan()).isOne();
        verify(candidates).readyAfter(first, 10, NOW);
    }

    private SellerWeeklyHistoricalAiPlanningService planner() {
        return new SellerWeeklyHistoricalAiPlanningService(candidates, reviews, jobs, preparation,
                WeeklyReviewAiTestProperties.properties(true, true, true), yandex, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Candidate candidate(String date) {
        return new Candidate(UUID.randomUUID(), UUID.randomUUID(), LocalDate.parse(date), UUID.randomUUID());
    }

    private void ready(Candidate candidate) {
        var report = report(candidate);
        when(reviews.period(candidate.storeId(), candidate.periodStart())).thenReturn(new SellerWeeklyReviewView(
                SellerWeeklyReviewView.Freshness.CURRENT, report));
        when(yandex.getModelUri()).thenReturn("synthetic-model");
        when(jobs.enqueueAutomaticSellerWeek(candidate.snapshotId(), "YANDEX", "synthetic-model", 2,
                NOW, Duration.ofHours(2))).thenReturn(true);
    }

    private WeeklyReviewV3Response report(Candidate candidate) {
        var report = mock(WeeklyReviewV3Response.class);
        when(report.membership()).thenReturn(new WeeklyReviewV3Response.Membership(
                SellerWeeklyHistoricalMembership.BASIS, "a".repeat(64), "a".repeat(64), "b".repeat(64), NOW, 1));
        var period = mock(WeeklyReviewResponse.PeriodContext.class);
        var current = mock(WeeklyReviewResponse.DateRange.class);
        when(current.start()).thenReturn(candidate.periodStart());
        when(period.current()).thenReturn(current);
        when(report.period()).thenReturn(period);
        when(report.provenance()).thenReturn(new WeeklyReviewResponse.Provenance(
                candidate.snapshotId().toString(), 1, NOW, NOW, false, null));
        when(report.reportState()).thenReturn(WeeklyReviewResponse.ReportState.READY);
        var enhancement = SellerWeeklyV3AssemblerTest.syntheticResponse().aiEnhancement();
        when(report.aiEnhancement()).thenReturn(enhancement);
        return report;
    }
}
