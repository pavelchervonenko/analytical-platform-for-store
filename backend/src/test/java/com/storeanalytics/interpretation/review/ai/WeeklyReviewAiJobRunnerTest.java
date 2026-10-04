package com.storeanalytics.interpretation.review.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class WeeklyReviewAiJobRunnerTest {

    @Test
    void concurrentInvocationCannotClaimAnOrphanJobWhileHeartbeatRemainsAvailable() throws Exception {
        Instant now = Instant.parse("2026-10-05T06:00:00Z");
        WeeklyReviewAiJobStore store = mock(WeeklyReviewAiJobStore.class);
        WeeklyReviewAiGenerationExecutionService execution = mock(WeeklyReviewAiGenerationExecutionService.class);
        WeeklyReviewAiGenerationProperties properties = WeeklyReviewAiTestProperties.properties(true, false, true);
        WeeklyReviewAiJob job = new WeeklyReviewAiJob(UUID.randomUUID(), UUID.randomUUID(),
                WeeklyReviewAiContract.PROMPT_VERSION, WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION,
                "YANDEX", "synthetic-model", WeeklyReviewAiJobStatus.RUNNING, 0, 1, now,
                now.plus(Duration.ofHours(2)), "first", now.plus(Duration.ofMinutes(4)),
                null, null, List.of(), now, now);
        WeeklyReviewAiJobRunner runner = new WeeklyReviewAiJobRunner(store, execution, properties,
                Clock.fixed(now, ZoneOffset.UTC));
        when(store.claimNext(anyString(), any(), any())).thenReturn(Optional.of(job), Optional.empty());
        when(store.findById(job.id())).thenReturn(Optional.of(job));
        when(store.heartbeat(job.id(), "first", properties.leaseDuration(), now)).thenReturn(true);
        CountDownLatch executing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);
        doAnswer(invocation -> {
            executing.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(execution).execute(job, "first");
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> runner.runNext("first"));
            assertThat(executing.await(10, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> {
                secondEntered.countDown();
                try {
                    return runner.runNext("second");
                } finally {
                    secondFinished.countDown();
                }
            });
            assertThat(secondEntered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(secondFinished.await(100, TimeUnit.MILLISECONDS)).isFalse();
            verify(store, times(1)).claimNext(anyString(), any(), any());
            assertThat(runner.heartbeatCurrent()).isTrue();
            verify(store).heartbeat(job.id(), "first", properties.leaseDuration(), now);
            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).contains(job);
            assertThat(second.get(10, TimeUnit.SECONDS)).isEmpty();
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
