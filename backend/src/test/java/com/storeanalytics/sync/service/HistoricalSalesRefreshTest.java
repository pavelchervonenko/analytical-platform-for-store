package com.storeanalytics.sync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.common.config.HistoricalSalesRefreshProperties;
import com.storeanalytics.integration.connection.model.IntegrationConnection;
import com.storeanalytics.integration.livesklad.client.HistoricalSalesReadScope;
import com.storeanalytics.sync.exception.HistoricalSalesReadBudgetException;
import com.storeanalytics.sync.model.SourceSystem;
import com.storeanalytics.sync.model.SyncJob;
import com.storeanalytics.sync.model.SyncJobDefinition;
import com.storeanalytics.sync.model.SyncJobPhase;
import com.storeanalytics.sync.model.SyncJobStatus;
import com.storeanalytics.sync.model.SyncJobType;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class HistoricalSalesRefreshTest {
    private static final Instant START = Instant.parse("2026-09-30T22:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-06T06:00:00Z");
    private static final UUID JOB_ID = UUID.randomUUID();

    @Test
    void historicalWindowIncludesOldDateWithoutPretendingItIsIncremental() {
        SyncJob historical = job(SyncJobType.HISTORICAL_SALES, 180);
        assertThat(historical.getPhase()).isEqualTo(SyncJobPhase.SALES);
        assertThat(historical.getPeriodStart()).isEqualTo(START);
        assertThat(START).isBefore(Instant.parse("2026-10-02T22:00:00Z"));
        historical.claim("worker", Duration.ofHours(2), NOW);
        historical.completeStep("worker", NOW.plusSeconds(1));
        assertThat(historical.getStatus()).isEqualTo(SyncJobStatus.SUCCESS);
        assertThat(historical.getCompletedSteps()).isEqualTo(1);
        assertThat(job(SyncJobType.INCREMENTAL, 180).getPhase()).isEqualTo(SyncJobPhase.STORES);
        assertThat(job(SyncJobType.BACKFILL, 180).getPhase()).isEqualTo(SyncJobPhase.STORES);
    }

    @Test
    void denseHistoricalIntervalShortensRequestedEndBeforeAnyCursorProgress() {
        SyncJob job = job(SyncJobType.HISTORICAL_SALES, 180);
        Instant originalEnd = job.getPeriodEnd();
        job.claim("worker", Duration.ofHours(2), NOW);
        assertThat(job.shrinkCurrentWindow("worker", NOW)).isTrue();
        assertThat(job.getCursorStart()).isEqualTo(START);
        assertThat(job.getPeriodEnd()).isEqualTo(START.plus(Duration.ofMinutes(90))).isBefore(originalEnd);
        assertThat(job.getCurrentWindowEnd()).isEqualTo(job.getPeriodEnd());
        assertThat(job.getStatus()).isEqualTo(SyncJobStatus.PENDING);
        job.claim("worker", Duration.ofHours(2), NOW);
        job.completeStep("worker", NOW);
        assertThat(job.getStatus()).isEqualTo(SyncJobStatus.SUCCESS);
        assertThat(job.getCursorStart()).isEqualTo(START.plus(Duration.ofMinutes(90)));
    }

    @Test
    void fractionalHalvingStopsAtExactlyFifteenMinutes() {
        SyncJob job = job(SyncJobType.HISTORICAL_SALES, 45);
        job.claim("worker", Duration.ofHours(2), NOW);
        assertThat(job.shrinkCurrentWindow("worker", NOW)).isTrue();
        job.claim("worker", Duration.ofHours(2), NOW);
        assertThat(job.shrinkCurrentWindow("worker", NOW)).isTrue();
        assertThat(job.getPeriodEnd()).isEqualTo(START.plus(Duration.ofMinutes(15)));
        job.claim("worker", Duration.ofHours(2), NOW);
        assertThat(job.shrinkCurrentWindow("worker", NOW)).isFalse();
    }

    @Test
    void ordinaryAdaptiveJobPreservesItsRequestedEnd() {
        SyncJob job = job(SyncJobType.BACKFILL, 180);
        Instant end = job.getPeriodEnd();
        job.claim("worker", Duration.ofHours(2), NOW);
        job.shrinkCurrentWindow("worker", NOW);
        assertThat(job.getPeriodEnd()).isEqualTo(end);
        assertThat(job.getCurrentWindowEnd()).isBefore(end);
    }

    @Test
    void cancellationCannotBeReportedAsSuccessfulCursorProgress() {
        SyncJob job = job(SyncJobType.HISTORICAL_SALES, 180);
        job.claim("worker", Duration.ofHours(2), NOW);
        job.requestCancellation(NOW);
        job.completeStep("worker", NOW);
        assertThat(job.getStatus()).isEqualTo(SyncJobStatus.CANCELLED);
        assertThat(job.getCursorStart()).isEqualTo(START);
    }

    @Test
    void dailyBudgetPausePreservesOnlyAttemptUntilBusinessMidnightEvenWithMaxAttemptsOne() {
        var connection = new IntegrationConnection("test", SourceSystem.LIVESKLAD, "Fixture", null, null);
        var job = SyncJob.create(new SyncJobDefinition(connection, null, SyncJobType.HISTORICAL_SALES,
                START, START.plusSeconds(10800), Duration.ofHours(3), 1), NOW);
        job.claim("worker", Duration.ofHours(2), NOW);
        Instant reset = Instant.parse("2026-10-06T22:00:01Z");
        job.pauseHistoricalDailyBudget("worker", reset, NOW);
        assertThat(job.getStatus()).isEqualTo(SyncJobStatus.WAITING_RETRY);
        assertThat(job.getAttemptCount()).isZero();
        assertThat(job.getTotalRetries()).isZero();
        assertThat(job.getNextAttemptAt()).isEqualTo(reset);
        job.claim("worker", Duration.ofHours(2), reset);
        job.completeStep("worker", reset);
        assertThat(job.getStatus()).isEqualTo(SyncJobStatus.SUCCESS);
    }

    @Test
    void realLeaseGuardRejectsCancelledExpiredWrongOwnerAttemptAndWindow() {
        SyncJob job = job(SyncJobType.HISTORICAL_SALES, 180);
        job.claim("worker", Duration.ofHours(2), NOW);
        var context = context("worker", 0, job.getPeriodEnd());
        HistoricalSalesRefreshService.validateLease(job, context, NOW);
        assertThatThrownBy(() -> HistoricalSalesRefreshService.validateLease(job,
                context("other-worker", 0, job.getPeriodEnd()), NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> HistoricalSalesRefreshService.validateLease(job,
                context("worker", 1, job.getPeriodEnd()), NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> HistoricalSalesRefreshService.validateLease(job,
                context("worker", 0, job.getPeriodEnd().minusSeconds(1)), NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> HistoricalSalesRefreshService.validateLease(job,
                context, NOW.plus(Duration.ofHours(2)))).isInstanceOf(IllegalStateException.class);
        job.requestCancellation(NOW);
        assertThatThrownBy(() -> HistoricalSalesRefreshService.validateLease(job, context, NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void stepQuotaStopsTheNextAttemptAndAlwaysClearsThreadScope() {
        AtomicInteger charged = new AtomicInteger();
        try (var ignored = HistoricalSalesReadScope.open(context("worker", 0, START.plusSeconds(900)),
                2, charged::incrementAndGet)) {
            HistoricalSalesReadScope.beforeRequest();
            HistoricalSalesReadScope.beforeRequest();
            assertThatThrownBy(HistoricalSalesReadScope::beforeRequest)
                    .isInstanceOf(HistoricalSalesReadBudgetException.class);
            assertThat(charged.get()).isEqualTo(2);
            assertThatThrownBy(() -> HistoricalSalesReadScope.open(context("worker", 0, START.plusSeconds(900)),
                    1, charged::incrementAndGet)).isInstanceOf(IllegalStateException.class);
        }
        assertThat(HistoricalSalesReadScope.current()).isNull();
        HistoricalSalesReadScope.beforeRequest();
        assertThat(charged.get()).isEqualTo(2);
    }

    @Test
    void quotaChargeFailureDoesNotPermitAnHttpAttempt() {
        AtomicInteger calls = new AtomicInteger();
        try (var ignored = HistoricalSalesReadScope.open(context("worker", 0, START.plusSeconds(900)), 2,
                () -> {
                    throw new HistoricalSalesReadBudgetException(Duration.ofHours(1), true);
                })) {
            assertThatThrownBy(() -> {
                HistoricalSalesReadScope.beforeRequest();
                calls.incrementAndGet();
            }).isInstanceOf(HistoricalSalesReadBudgetException.class);
            assertThat(calls).hasValue(0);
        }
    }

    @Test
    void activationRequiresExplicitStartAndBoundedConfiguration() {
        assertThat(new HistoricalSalesRefreshProperties(false, null, 180, 100, 200, Duration.ofDays(1)).enabled())
                .isFalse();
        assertThatThrownBy(() -> new HistoricalSalesRefreshProperties(true, null, 180, 100, 200, Duration.ofDays(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HistoricalSalesRefreshProperties(true, LocalDate.of(2026, 10, 1),
                14, 100, 200, Duration.ofDays(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HistoricalSalesRefreshProperties(true, LocalDate.of(2026, 10, 1),
                180, 100, 99, Duration.ofDays(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HistoricalSalesRefreshProperties(true, LocalDate.of(2026, 10, 1),
                180, 101, 200, Duration.ofDays(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HistoricalSalesRefreshProperties(true, LocalDate.of(2026, 10, 1),
                180, 100, 201, Duration.ofDays(1))).isInstanceOf(IllegalArgumentException.class);
        var inactive = new HistoricalSalesRefreshService(
                new HistoricalSalesRefreshProperties(false, null, 180, 100, 200, Duration.ofDays(1)),
                null, null, null, null, null, null);
        assertThat(inactive.enqueue()).isEmpty();
    }

    private static HistoricalSalesReadScope.Context context(String worker, int attempt, Instant end) {
        return new HistoricalSalesReadScope.Context(JOB_ID, worker, attempt, START, end);
    }

    private static SyncJob job(SyncJobType type, int minutes) {
        IntegrationConnection connection = new IntegrationConnection("test", SourceSystem.LIVESKLAD,
                "Fixture", null, null);
        SyncJob job = SyncJob.create(new SyncJobDefinition(connection, null, type, START,
                START.plus(Duration.ofMinutes(minutes)), Duration.ofMinutes(minutes), 5), NOW);
        ReflectionTestUtils.setField(job, "id", JOB_ID);
        return job;
    }
}
