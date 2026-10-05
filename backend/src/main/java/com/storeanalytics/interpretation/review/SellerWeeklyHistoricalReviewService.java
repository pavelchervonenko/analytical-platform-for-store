package com.storeanalytics.interpretation.review;

import com.storeanalytics.interpretation.review.ai.SellerWeeklyReviewAiContract;
import com.storeanalytics.interpretation.review.ai.SellerWeeklyReviewAiReadSupport;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Public period adapter; read is side-effect free, refresh is an internal exact free operation only. */
@Service
public class SellerWeeklyHistoricalReviewService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SellerWeeklyHistoricalReviewService.class);
    private final SellerWeeklyHistoricalReadService reads;
    private final SellerWeeklyHistoricalPlanningService planner;
    private final SellerWeeklyReviewAiReadSupport ai;
    private final Clock clock;

    SellerWeeklyHistoricalReviewService(SellerWeeklyHistoricalReadService reads,
            SellerWeeklyHistoricalPlanningService planner, SellerWeeklyReviewAiReadSupport ai, Clock clock) {
        this.reads = reads;
        this.planner = planner;
        this.ai = ai;
        this.clock = clock;
    }

    public SellerWeeklyReviewView period(UUID storeId, LocalDate start) {
        var view = SellerWeeklyReviewView.from(reads.assess(storeId, start));
        if (view.freshness() != SellerWeeklyReviewView.Freshness.CURRENT || !ai.properties().enabled()) {
            return view;
        }
        try {
            var now = clock.instant();
            var pending = new SellerWeeklyReviewView(view.freshness(), ai.states().apply(view.report(), now));
            return ai.enrichments().findPublishedSeller(
                            UUID.fromString(view.report().provenance().snapshotPublicId()), now)
                    .flatMap(value -> ai.enricher().applyIfCompatible(view.report(), value))
                    .map(report -> new SellerWeeklyReviewView(view.freshness(), report)).orElse(pending);
        } catch (RuntimeException unavailable) {
            LOGGER.warn("Historical seller read ignored unavailable optional AI; failureType={}",
                    unavailable.getClass().getSimpleName());
            return new SellerWeeklyReviewView(view.freshness(), view.report().withAiEnhancement(
                    new WeeklyReviewResponse.AiEnhancement(WeeklyReviewResponse.AiState.UNAVAILABLE,
                            SellerWeeklyReviewAiContract.PROMPT_VERSION, 4, null)));
        }
    }

    /** Not an HTTP write endpoint; cannot enqueue, change a baseline or create a paid attempt. */
    public SellerWeeklyReviewView refresh(UUID storeId, LocalDate start, String expectedTimezone) {
        return SellerWeeklyReviewView.from(planner.evaluate(storeId, start, expectedTimezone).review());
    }
}
