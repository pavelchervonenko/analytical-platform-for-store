package com.storeanalytics.interpretation.review.ai;

import com.storeanalytics.common.config.ApplicationRole;
import com.storeanalytics.common.config.ConditionalOnApplicationRole;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnApplicationRole({ApplicationRole.WORKER, ApplicationRole.COMBINED})
@ConditionalOnProperty(
        prefix = "app.interpretation.weekly-review-ai",
        name = {"enabled", "worker-enabled"},
        havingValue = "true"
)
public class WeeklyReviewAiWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            WeeklyReviewAiWorker.class
    );

    private final String workerId = UUID.randomUUID().toString();
    private final WeeklyReviewAiJobRunner runner;

    public WeeklyReviewAiWorker(WeeklyReviewAiJobRunner runner) {
        this.runner = runner;
    }

    @Scheduled(
            fixedDelayString = "${app.interpretation.weekly-review-ai.worker-delay:5s}",
            scheduler = WeeklyReviewAiSchedulingConfiguration.WORKER_SCHEDULER
    )
    public void processNext() {
        try {
            runner.runNext(workerId);
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "Weekly review AI worker step failed; failure_type={}",
                    exception.getClass().getSimpleName()
            );
        }
    }

    @Scheduled(
            fixedDelayString = "${app.interpretation.weekly-review-ai.heartbeat-interval:30s}",
            scheduler = WeeklyReviewAiSchedulingConfiguration.HEARTBEAT_SCHEDULER
    )
    public void heartbeat() {
        try {
            if (!runner.heartbeatCurrent()) {
                LOGGER.error("Weekly review AI worker lost its active lease");
            }
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "Weekly review AI worker heartbeat failed; failure_type={}",
                    exception.getClass().getSimpleName()
            );
        }
    }
}
