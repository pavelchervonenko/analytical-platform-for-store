package com.storeanalytics.interpretation.review;

import com.storeanalytics.common.config.ApplicationRole;
import com.storeanalytics.common.config.ConditionalOnApplicationRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnApplicationRole({ApplicationRole.WORKER, ApplicationRole.COMBINED})
@ConditionalOnProperty(
        prefix = "app.interpretation.weekly-review-snapshot-planner",
        name = "enabled",
        havingValue = "true"
)
public class WeeklyReviewSnapshotPlanner {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            WeeklyReviewSnapshotPlanner.class
    );

    private final WeeklyReviewSnapshotPlanningService planningService;

    public WeeklyReviewSnapshotPlanner(
            WeeklyReviewSnapshotPlanningService planningService
    ) {
        this.planningService = planningService;
    }

    @Scheduled(
            fixedDelayString = "${app.interpretation."
                    + "weekly-review-snapshot-planner.scan-delay:5m}",
            scheduler = WeeklyReviewSnapshotSchedulingConfiguration.SCHEDULER
    )
    public void reconcile() {
        try {
            WeeklyReviewSnapshotPlanningResult result = planningService.plan();
            if (result.snapshotsCreated() > 0
                    || result.revisionsCreated() > 0
                    || result.failures() > 0
                    || result.invalidStores() > 0) {
                LOGGER.info(
                        "Weekly review snapshot planning completed; result={}",
                        result
                );
            }
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "Weekly review snapshot planning iteration failed",
                    exception
            );
        }
    }
}
