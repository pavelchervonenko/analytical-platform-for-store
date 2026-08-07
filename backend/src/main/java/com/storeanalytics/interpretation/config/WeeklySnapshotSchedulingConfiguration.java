package com.storeanalytics.interpretation.config;

import com.storeanalytics.common.config.ApplicationRole;
import com.storeanalytics.common.config.ConditionalOnApplicationRole;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnApplicationRole({ApplicationRole.WORKER, ApplicationRole.COMBINED})
@ConditionalOnProperty(
        prefix = "app.interpretation.snapshot-worker",
        name = "enabled",
        havingValue = "true"
)
public class WeeklySnapshotSchedulingConfiguration {

    public static final String SNAPSHOT_WORKER_SCHEDULER =
            "weeklySnapshotWorkerScheduler";
    public static final String SNAPSHOT_HEARTBEAT_SCHEDULER =
            "weeklySnapshotHeartbeatScheduler";

    @Bean(name = SNAPSHOT_WORKER_SCHEDULER)
    public ThreadPoolTaskScheduler weeklySnapshotWorkerScheduler() {
        return scheduler("weekly-snapshot-worker-");
    }

    @Bean(name = SNAPSHOT_HEARTBEAT_SCHEDULER)
    public ThreadPoolTaskScheduler weeklySnapshotHeartbeatScheduler() {
        return scheduler("weekly-snapshot-heartbeat-");
    }

    private ThreadPoolTaskScheduler scheduler(String threadNamePrefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }
}
