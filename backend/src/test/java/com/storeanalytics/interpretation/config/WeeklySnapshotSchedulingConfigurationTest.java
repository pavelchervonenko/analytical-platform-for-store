package com.storeanalytics.interpretation.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class WeeklySnapshotSchedulingConfigurationTest {

    @Test
    void createsIndependentSingleThreadWorkerAndHeartbeatSchedulers() {
        WeeklySnapshotSchedulingConfiguration configuration =
                new WeeklySnapshotSchedulingConfiguration();
        ThreadPoolTaskScheduler worker = configuration.weeklySnapshotWorkerScheduler();
        ThreadPoolTaskScheduler heartbeat =
                configuration.weeklySnapshotHeartbeatScheduler();
        worker.initialize();
        heartbeat.initialize();
        try {
            assertScheduler(worker, "weekly-snapshot-worker-");
            assertScheduler(heartbeat, "weekly-snapshot-heartbeat-");
        } finally {
            worker.shutdown();
            heartbeat.shutdown();
        }
    }

    private void assertScheduler(
            ThreadPoolTaskScheduler scheduler,
            String threadNamePrefix
    ) {
        assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize())
                .isOne();
        assertThat(scheduler.getThreadNamePrefix()).isEqualTo(threadNamePrefix);
        assertThat(scheduler.getScheduledThreadPoolExecutor().getRemoveOnCancelPolicy())
                .isTrue();
    }
}
