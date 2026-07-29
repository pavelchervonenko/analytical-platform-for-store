package com.storeanalytics.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnApplicationRole({ApplicationRole.WORKER, ApplicationRole.COMBINED})
public class BackgroundSchedulingConfiguration {

    public static final String SYNC_CONTROL_SCHEDULER = "syncControlScheduler";
    public static final String LIVESKLAD_PROBE_SCHEDULER =
            "liveSkladProbeScheduler";
    public static final String ANNUAL_REPORT_SCHEDULER =
            "annualReportTaskScheduler";
    public static final String RETENTION_SCHEDULER = "retentionScheduler";
    public static final String METRICS_SCHEDULER = "metricsScheduler";
    public static final String CLEANUP_SCHEDULER = "cleanupScheduler";

    @Bean(name = SYNC_CONTROL_SCHEDULER)
    public ThreadPoolTaskScheduler syncControlScheduler() {
        return scheduler("sync-control-");
    }

    @Bean(name = LIVESKLAD_PROBE_SCHEDULER)
    public ThreadPoolTaskScheduler liveSkladProbeScheduler() {
        return scheduler("livesklad-probe-");
    }

    @Bean(name = ANNUAL_REPORT_SCHEDULER)
    public ThreadPoolTaskScheduler annualReportTaskScheduler() {
        return scheduler("annual-report-");
    }

    @Bean(name = RETENTION_SCHEDULER)
    public ThreadPoolTaskScheduler retentionScheduler() {
        return scheduler("retention-");
    }

    @Bean(name = METRICS_SCHEDULER)
    public ThreadPoolTaskScheduler metricsScheduler() {
        return scheduler("metrics-refresh-");
    }

    @Bean(name = CLEANUP_SCHEDULER)
    public ThreadPoolTaskScheduler cleanupScheduler() {
        return scheduler("cleanup-");
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
