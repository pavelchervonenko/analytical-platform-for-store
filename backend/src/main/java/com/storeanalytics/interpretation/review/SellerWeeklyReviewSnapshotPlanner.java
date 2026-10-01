package com.storeanalytics.interpretation.review;

import com.storeanalytics.common.config.ApplicationRole;
import com.storeanalytics.common.config.ConditionalOnApplicationRole;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded local sweep. A restart safely restarts the sweep; identities avoid duplicate snapshots. */
@Component
@ConditionalOnApplicationRole({ApplicationRole.WORKER, ApplicationRole.COMBINED})
@ConditionalOnProperty(prefix = "app.interpretation.seller-weekly-review", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "app.interpretation.weekly-review-snapshot-planner",
        name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "app.interpretation.weekly-review", name = "enabled", havingValue = "true")
class SellerWeeklyReviewSnapshotPlanner {
    private static final Logger LOGGER = LoggerFactory.getLogger(SellerWeeklyReviewSnapshotPlanner.class);
    private final SellerWeeklyV3BatchPlanningService batch;
    private final SellerWeeklyV3BatchPlanningService.Budget budget;
    private UUID cursor;

    SellerWeeklyReviewSnapshotPlanner(SellerWeeklyV3BatchPlanningService batch,
                                      WeeklyReviewSnapshotPlannerProperties properties) {
        this.batch = batch;
        this.budget = new SellerWeeklyV3BatchPlanningService.Budget(100,
                Math.min(25, properties.batchSize()), Duration.ofMinutes(1));
    }

    @Scheduled(fixedDelayString = "${app.interpretation.weekly-review-snapshot-planner.scan-delay:5m}",
            scheduler = WeeklyReviewSnapshotSchedulingConfiguration.SCHEDULER)
    synchronized void reconcile() {
        try {
            var result = batch.scan(cursor, budget);
            cursor = result.stopReason() == SellerWeeklyV3BatchPlanningResult.StopReason.EXHAUSTED
                    ? null : result.afterStoreId();
            LOGGER.info("Seller weekly planning; scanned={}, evaluated={}, unchanged={}, deferred={}, stop={}",
                    result.scanned(), result.evaluated(), result.unchanged(), result.deferred(), result.stopReason());
        } catch (RuntimeException failure) {
            // Resume from the previous safe cursor; previously committed stores become UNCHANGED.
            LOGGER.error("Seller weekly planning failed; failureType={}", failure.getClass().getSimpleName());
        }
    }
}
