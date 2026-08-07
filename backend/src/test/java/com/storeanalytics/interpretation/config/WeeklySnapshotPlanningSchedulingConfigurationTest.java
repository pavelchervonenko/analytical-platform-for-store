package com.storeanalytics.interpretation.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class WeeklySnapshotPlanningSchedulingConfigurationTest {

    @Test
    void createsDedicatedSingleThreadPlannerScheduler() {
        WeeklySnapshotPlanningSchedulingConfiguration configuration =
                new WeeklySnapshotPlanningSchedulingConfiguration();
        ThreadPoolTaskScheduler scheduler =
                configuration.weeklySnapshotPlanningScheduler();
        scheduler.initialize();
        try {
            assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize())
                    .isOne();
            assertThat(scheduler.getThreadNamePrefix())
                    .isEqualTo("weekly-snapshot-planning-");
            assertThat(scheduler.getScheduledThreadPoolExecutor().getRemoveOnCancelPolicy())
                    .isTrue();
        } finally {
            scheduler.shutdown();
        }
    }
}
