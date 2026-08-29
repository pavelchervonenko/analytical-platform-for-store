package com.storeanalytics.interpretation.review.ai;

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
        prefix = "app.interpretation.weekly-review-ai",
        name = {"enabled", "planner-enabled"},
        havingValue = "true"
)
public class WeeklyReviewAiPlanner {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            WeeklyReviewAiPlanner.class
    );

    private final WeeklyReviewAiPlanningService planningService;

    public WeeklyReviewAiPlanner(
            WeeklyReviewAiPlanningService planningService
    ) {
        this.planningService = planningService;
    }

    @Scheduled(
            fixedDelayString = "${app.interpretation.weekly-review-ai.scan-delay:1m}",
            scheduler = WeeklyReviewAiSchedulingConfiguration.PLANNER_SCHEDULER
    )
    public void reconcile() {
        try {
            int created = planningService.plan();
            if (created > 0) {
                LOGGER.info("Weekly review AI planning created jobs; count={}", created);
            }
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "Weekly review AI planning iteration failed; failure_type={}",
                    exception.getClass().getSimpleName()
            );
        }
    }
}
