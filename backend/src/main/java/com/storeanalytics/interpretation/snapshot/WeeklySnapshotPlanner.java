package com.storeanalytics.interpretation.snapshot;

import com.storeanalytics.common.config.ApplicationRole;
import com.storeanalytics.common.config.ConditionalOnApplicationRole;
import com.storeanalytics.interpretation.config.WeeklySnapshotPlanningSchedulingConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnApplicationRole({ApplicationRole.WORKER, ApplicationRole.COMBINED})
@ConditionalOnProperty(
        prefix = "app.interpretation.snapshot-planner",
        name = "enabled",
        havingValue = "true"
)
public class WeeklySnapshotPlanner {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            WeeklySnapshotPlanner.class
    );

    private final WeeklySnapshotPlanningService planningService;

    public WeeklySnapshotPlanner(WeeklySnapshotPlanningService planningService) {
        this.planningService = planningService;
    }

    @Scheduled(
            fixedDelayString = "${app.interpretation.snapshot-planner.scan-delay:1m}",
            scheduler = WeeklySnapshotPlanningSchedulingConfiguration
                    .SNAPSHOT_PLANNING_SCHEDULER
    )
    public void reconcile() {
        try {
            WeeklySnapshotPlanningResult result = planningService.plan();
            if (result.requestsAccepted() > 0 || result.conflicts() > 0
                    || result.invalidStores() > 0) {
                LOGGER.info("Weekly snapshot planning completed; result={}", result);
            }
        } catch (RuntimeException exception) {
            LOGGER.error("Weekly snapshot planning iteration failed", exception);
        }
    }
}
