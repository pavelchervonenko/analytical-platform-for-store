package com.storeanalytics.interpretation.review.ai;

import com.storeanalytics.common.config.ApplicationRole;
import com.storeanalytics.common.config.ConditionalOnApplicationRole;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnApplicationRole({ApplicationRole.WORKER, ApplicationRole.COMBINED})
@ConditionalOnProperty(
        prefix = "app.interpretation.weekly-review-ai",
        name = "enabled",
        havingValue = "true"
)
public class WeeklyReviewAiSchedulingConfiguration {

    public static final String PLANNER_SCHEDULER =
            "weeklyReviewAiPlannerScheduler";
    public static final String WORKER_SCHEDULER =
            "weeklyReviewAiWorkerScheduler";
    public static final String HEARTBEAT_SCHEDULER =
            "weeklyReviewAiHeartbeatScheduler";

    @Bean(name = PLANNER_SCHEDULER)
    ThreadPoolTaskScheduler plannerScheduler() {
        return scheduler("weekly-review-ai-planner-");
    }

    @Bean(name = WORKER_SCHEDULER)
    ThreadPoolTaskScheduler workerScheduler() {
        return scheduler("weekly-review-ai-worker-");
    }

    @Bean(name = HEARTBEAT_SCHEDULER)
    ThreadPoolTaskScheduler heartbeatScheduler() {
        return scheduler("weekly-review-ai-heartbeat-");
    }

    private ThreadPoolTaskScheduler scheduler(String prefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(prefix);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }
}
