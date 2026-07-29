package com.storeanalytics.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class BackgroundSchedulingConfigurationTest {

    @Test
    void saturatedExternalProbeDoesNotStarveMetrics() throws InterruptedException {
        BackgroundSchedulingConfiguration configuration =
                new BackgroundSchedulingConfiguration();
        ThreadPoolTaskScheduler probe = configuration.liveSkladProbeScheduler();
        ThreadPoolTaskScheduler metrics = configuration.metricsScheduler();
        CountDownLatch probeStarted = new CountDownLatch(1);
        CountDownLatch releaseProbe = new CountDownLatch(1);
        CountDownLatch metricsCompleted = new CountDownLatch(1);
        probe.initialize();
        metrics.initialize();
        try {
            probe.execute(() -> {
                probeStarted.countDown();
                try {
                    releaseProbe.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(probeStarted.await(1, TimeUnit.SECONDS)).isTrue();

            metrics.execute(metricsCompleted::countDown);

            assertThat(metricsCompleted.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseProbe.countDown();
            probe.shutdown();
            metrics.shutdown();
        }
    }

    @Test
    void createsIndependentSingleConcurrencyBulkheads() {
        BackgroundSchedulingConfiguration configuration =
                new BackgroundSchedulingConfiguration();
        List<ThreadPoolTaskScheduler> schedulers = List.of(
                configuration.syncControlScheduler(),
                configuration.liveSkladProbeScheduler(),
                configuration.annualReportTaskScheduler(),
                configuration.retentionScheduler(),
                configuration.metricsScheduler(),
                configuration.cleanupScheduler()
        );

        schedulers.forEach(ThreadPoolTaskScheduler::initialize);
        try {
            assertThat(schedulers)
                    .extracting(scheduler -> scheduler
                            .getScheduledThreadPoolExecutor()
                            .getCorePoolSize())
                    .containsOnly(1);
            assertThat(schedulers)
                    .extracting(ThreadPoolTaskScheduler::getThreadNamePrefix)
                    .doesNotHaveDuplicates();
            assertThat(schedulers)
                    .allSatisfy(scheduler -> assertThat(scheduler
                            .getScheduledThreadPoolExecutor()
                            .getRemoveOnCancelPolicy()).isTrue());
        } finally {
            schedulers.forEach(ThreadPoolTaskScheduler::shutdown);
        }
    }
}
