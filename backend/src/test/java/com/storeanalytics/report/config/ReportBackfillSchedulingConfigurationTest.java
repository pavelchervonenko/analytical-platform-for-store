package com.storeanalytics.report.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class ReportBackfillSchedulingConfigurationTest {

    @Test
    void createsDedicatedSingleThreadScheduler() {
        ThreadPoolTaskScheduler scheduler =
                new ReportBackfillSchedulingConfiguration()
                        .reportBackfillScheduler();
        scheduler.initialize();
        try {
            assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize())
                    .isOne();
            assertThat(scheduler.getThreadNamePrefix())
                    .isEqualTo("report-backfill-");
            assertThat(scheduler.getScheduledThreadPoolExecutor()
                    .getRemoveOnCancelPolicy()).isTrue();
        } finally {
            scheduler.shutdown();
        }
    }
}
