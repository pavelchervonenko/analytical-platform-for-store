package com.storeanalytics.interpretation.review;

import com.storeanalytics.integration.llm.yandex.YandexLlmProperties;
import com.storeanalytics.interpretation.review.ai.WeeklyReviewAiGenerationProperties;
import com.storeanalytics.interpretation.review.ai.WeeklyReviewAiJobStore;
import java.time.Clock;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Historical-only paid planning; never generates snapshots, invents a baseline or calls the provider. */
@Service
public class SellerWeeklyHistoricalAiPlanningService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SellerWeeklyHistoricalAiPlanningService.class);
    private final SellerWeeklyAutomaticAiCandidates candidates;
    private final SellerWeeklyHistoricalReviewService reviews;
    private final WeeklyReviewAiJobStore jobs;
    private final SellerWeeklyPreparationProperties preparation;
    private final WeeklyReviewAiGenerationProperties ai;
    private final YandexLlmProperties yandex;
    private final Clock clock;
    private final LongSupplier nanoTime;
    private SellerWeeklyAutomaticAiCandidates.Candidate cursor;

    @Autowired
    public SellerWeeklyHistoricalAiPlanningService(SellerWeeklyAutomaticAiCandidates candidates,
            SellerWeeklyHistoricalReviewService reviews, WeeklyReviewAiJobStore jobs,
            SellerWeeklyPreparationProperties preparation, WeeklyReviewAiGenerationProperties ai,
            YandexLlmProperties yandex, Clock clock) {
        this(candidates, reviews, jobs, preparation, ai, yandex, new Timing(clock, System::nanoTime));
    }

    SellerWeeklyHistoricalAiPlanningService(SellerWeeklyAutomaticAiCandidates candidates,
            SellerWeeklyHistoricalReviewService reviews, WeeklyReviewAiJobStore jobs,
            SellerWeeklyPreparationProperties preparation, WeeklyReviewAiGenerationProperties ai,
            YandexLlmProperties yandex, Timing timing) {
        this.candidates = candidates;
        this.reviews = reviews;
        this.jobs = jobs;
        this.preparation = preparation;
        this.ai = ai;
        this.yandex = yandex;
        this.clock = timing.clock();
        this.nanoTime = timing.nanoTime();
    }

    record Timing(Clock clock, LongSupplier nanoTime) { }

    public boolean historicalModeEnabled() {
        return preparation.enabled();
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public synchronized int plan() {
        if (!preparation.enabled() || !ai.enabled() || !ai.plannerEnabled()) {
            return 0;
        }
        long started = nanoTime.getAsLong();
        var page = candidates.readyAfter(cursor, ai.batchSize(), clock.instant());
        int scanned = 0;
        int planned = 0;
        for (var candidate : page) {
            if (nanoTime.getAsLong() - started >= preparation.timeBudget().toNanos()) {
                break;
            }
            try {
                planned += plan(candidate) ? 1 : 0;
            } catch (RuntimeException failure) {
                LOGGER.error("Historical seller AI planning deferred; store_id={} period_start={} failure_type={}",
                        candidate.storeId(), candidate.periodStart(), failure.getClass().getSimpleName());
            }
            cursor = candidate;
            scanned++;
        }
        if (scanned == page.size() && page.size() < ai.batchSize()) {
            cursor = null;
        }
        return planned;
    }

    private boolean plan(SellerWeeklyAutomaticAiCandidates.Candidate candidate) {
        var review = reviews.period(candidate.storeId(), candidate.periodStart());
        if (review.freshness() != SellerWeeklyReviewView.Freshness.CURRENT) {
            return false;
        }
        var report = review.report();
        if (!SellerWeeklyHistoricalMembership.BASIS.equals(report.membership().basis())
                || !candidate.periodStart().equals(report.period().current().start())
                || !candidate.snapshotId().equals(UUID.fromString(report.provenance().snapshotPublicId()))
                || (report.reportState() != WeeklyReviewResponse.ReportState.READY
                    && report.reportState() != WeeklyReviewResponse.ReportState.PARTIAL)
                || report.aiEnhancement().state() == WeeklyReviewResponse.AiState.READY) {
            return false;
        }
        return jobs.enqueueAutomaticSellerWeek(candidate.snapshotId(), ai.providerCode(), yandex.getModelUri(),
                ai.maxProviderCalls(), clock.instant(), ai.jobDeadline());
    }
}
