package com.storeanalytics.interpretation.review;

import com.storeanalytics.metrics.exception.StoreNotFoundException;
import com.storeanalytics.interpretation.review.ai.SellerWeeklyReviewAiReadSupport;
import com.storeanalytics.interpretation.review.ai.SellerWeeklyReviewAiContract;
import com.storeanalytics.store.repository.StoreRepository;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Public adapter: GET never generates content, and generation always uses stable-source fences. */
@Service
public class SellerWeeklyReviewService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SellerWeeklyReviewService.class);
    private final StoreRepository stores;
    private final SellerWeeklyV3ReadService reads;
    private final SellerWeeklyV3PlanningService planner;
    private final SellerWeeklyReviewAiReadSupport aiSupport;
    private final Clock clock;
    private final SellerWeeklyCurrentRouting routing;
    private final SellerWeeklyHistoricalReviewService historical;

    SellerWeeklyReviewService(StoreRepository stores, SellerWeeklyV3ReadService reads,
                              SellerWeeklyV3PlanningService planner) {
        this(stores, reads, planner, null, Clock.systemUTC());
    }

    public SellerWeeklyReviewService(StoreRepository stores, SellerWeeklyV3ReadService reads,
                                    SellerWeeklyV3PlanningService planner,
                                    SellerWeeklyReviewAiReadSupport aiSupport, Clock clock) {
        this(stores, reads, planner, aiSupport, clock, null, null);
    }

    @Autowired
    SellerWeeklyReviewService(StoreRepository stores, SellerWeeklyV3ReadService reads,
            SellerWeeklyV3PlanningService planner, SellerWeeklyReviewAiReadSupport aiSupport, Clock clock,
            SellerWeeklyCurrentRouting routing, SellerWeeklyHistoricalReviewService historical) {
        this.stores = stores;
        this.reads = reads;
        this.planner = planner;
        this.aiSupport = aiSupport;
        this.clock = clock;
        this.routing = routing;
        this.historical = historical;
    }

    public SellerWeeklyReviewView current(UUID storeId) {
        requireStore(storeId);
        var target = routing == null ? java.util.Optional.<ClosedSellerWeek>empty() : routing.historicalTarget(storeId);
        if (target.isPresent()) {
            var week = target.orElseThrow();
            return currentPeriodView(historical.period(storeId, week.start()), week);
        }
        SellerWeeklyReviewView view = SellerWeeklyReviewView.from(reads.assessForPlanning(storeId));
        if (view.freshness() != SellerWeeklyReviewView.Freshness.CURRENT
                || aiSupport == null || !aiSupport.properties().enabled()) {
            return view;
        }
        try {
            SellerWeeklyReviewView pending = new SellerWeeklyReviewView(view.freshness(),
                    aiSupport.states().apply(view.report(), clock.instant()));
            return aiSupport.enrichments().findPublishedSeller(
                            UUID.fromString(view.report().provenance().snapshotPublicId()),
                            clock.instant())
                    .flatMap(value -> aiSupport.enricher().applyIfCompatible(view.report(), value))
                    .map(report -> new SellerWeeklyReviewView(view.freshness(), report)).orElse(pending);
        } catch (RuntimeException optionalAiFailure) {
            LOGGER.warn("Seller weekly review ignored unavailable optional enrichment; failureType={}",
                    optionalAiFailure.getClass().getSimpleName());
            return new SellerWeeklyReviewView(view.freshness(), view.report().withAiEnhancement(
                    new WeeklyReviewResponse.AiEnhancement(WeeklyReviewResponse.AiState.UNAVAILABLE,
                            SellerWeeklyReviewAiContract.PROMPT_VERSION, 4, null)));
        }
    }

    public SellerWeeklyReviewView generate(UUID storeId) {
        requireStore(storeId);
        var target = routing == null ? java.util.Optional.<ClosedSellerWeek>empty() : routing.historicalTarget(storeId);
        if (target.isPresent()) {
            var week = target.orElseThrow();
            return currentPeriodView(historical.refresh(storeId, week.start(), week.zone().getId()), week);
        }
        return SellerWeeklyReviewView.from(planner.evaluate(storeId).review());
    }

    private SellerWeeklyReviewView currentPeriodView(SellerWeeklyReviewView view, ClosedSellerWeek week) {
        if (routing.stillLatest(week) || view.report() == null) {
            return view;
        }
        return new SellerWeeklyReviewView(SellerWeeklyReviewView.Freshness.STALE, view.report());
    }

    private void requireStore(UUID storeId) {
        if (storeId == null || !stores.existsById(storeId)) {
            throw new StoreNotFoundException(storeId);
        }
    }
}
