package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.metrics.service.SellerHistoricalFactsUnavailableException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** One exact free period evaluation; unscheduled and independent from the paid job lifecycle. */
@Service
class SellerWeeklyHistoricalPlanningService {
    private final SellerWeeklyHistoricalReadService reads;
    private final SellerWeeklyHistoricalFactsSource facts;
    private final WeeklyReviewSnapshotStore snapshots;
    private final Clock clock;

    SellerWeeklyHistoricalPlanningService(SellerWeeklyHistoricalReadService reads,
            SellerWeeklyHistoricalFactsSource facts, WeeklyReviewSnapshotStore snapshots, Clock clock) {
        this.reads = reads;
        this.facts = facts;
        this.snapshots = snapshots;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SellerWeeklyV3PlanningResult evaluate(UUID storeId, LocalDate start, String expectedTimezone) {
        requireNonNull(storeId, "storeId");
        requireNonNull(start, "start");
        requireNonNull(expectedTimezone, "expectedTimezone");
        var before = reads.assess(storeId, start);
        if (before.state() == SellerWeeklyV3ReadResult.State.CURRENT
                && expectedTimezone.equals(before.snapshot().orElseThrow().response().period().timezone())) {
            return new SellerWeeklyV3PlanningResult(SellerWeeklyV3PlanningResult.Outcome.UNCHANGED, before);
        }
        try {
            var selected = facts.load(storeId, start, expectedTimezone, clock.instant());
            snapshots.persistHistoricalCandidate(selected, clock.instant());
            return new SellerWeeklyV3PlanningResult(SellerWeeklyV3PlanningResult.Outcome.EVALUATED,
                    reads.assess(storeId, start));
        } catch (SellerHistoricalFactsUnavailableException | SellerWeeklySourceChangedException unavailable) {
            return new SellerWeeklyV3PlanningResult(SellerWeeklyV3PlanningResult.Outcome.DEFERRED,
                    reads.assess(storeId, start));
        }
    }
}
