package com.storeanalytics.sync.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class SyncWorkerSchedulingConfigurationTest {

    @Test
    void createsSingleThreadedBoundedWorkerScheduler() {
        ThreadPoolTaskScheduler scheduler =
                new SyncWorkerSchedulingConfiguration().syncWorkerScheduler();
        scheduler.initialize();
        try {
            assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize())
                    .isOne();
            assertThat(scheduler.getThreadNamePrefix()).isEqualTo("sync-worker-");
            assertThat(scheduler.getScheduledThreadPoolExecutor()
                    .getRemoveOnCancelPolicy()).isTrue();
        } finally {
            scheduler.shutdown();
        }
    }
}
