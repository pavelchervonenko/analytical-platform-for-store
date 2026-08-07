package com.storeanalytics.interpretation.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class LlmAnalysisJobLifecycleIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-03T05:00:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private LlmAnalysisJobStore jobStore;

    @Autowired
    private LlmAnalysisJobClaimStore claimStore;

    @Autowired
    private LlmAnalysisJobLifecycleStore lifecycleStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void lifecycleIsConcurrentCrashSafeDeadlineBoundedAndCancellable()
            throws Exception {
        UUID cancellableSnapshot = createSnapshot(NOW);
        LlmAnalysisJob queued = jobStore.enqueue(
                request(cancellableSnapshot, NOW.plus(Duration.ofMinutes(5))),
                NOW
        ).job();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Optional<LlmAnalysisJob>> claims;
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Optional<LlmAnalysisJob>> first = executor.submit(() ->
                    concurrentClaim("worker-1", ready, start));
            Future<Optional<LlmAnalysisJob>> second = executor.submit(() ->
                    concurrentClaim("worker-2", ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            claims = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        assertThat(claims).filteredOn(Optional::isPresent).hasSize(1);
        LlmAnalysisJob claimed = claims.stream().flatMap(Optional::stream).findFirst().orElseThrow();
        assertThat(claimed.id()).isEqualTo(queued.id());
        assertThat(claimed.status()).isEqualTo(LlmAnalysisJobStatus.RUNNING);
        assertThat(claimed.attemptCount()).isOne();
        assertThat(claimed.leaseUntil()).isEqualTo(claimed.deadlineAt());

        String owner = claimed.leaseOwner();
        LlmAnalysisJob heartbeat = lifecycleStore.heartbeat(
                claimed.id(), owner, Duration.ofMinutes(10), NOW.plusSeconds(60)
        );
        assertThat(heartbeat.leaseUntil()).isEqualTo(claimed.deadlineAt());
        assertThatThrownBy(() -> lifecycleStore.heartbeat(
                claimed.id(), "another-worker", Duration.ofMinutes(1), NOW.plusSeconds(61)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("owned elsewhere");

        LlmAnalysisJob cancellation = lifecycleStore.requestCancellation(
                claimed.id(), NOW.plusSeconds(90)
        );
        assertThat(cancellation.status()).isEqualTo(LlmAnalysisJobStatus.RUNNING);
        assertThat(cancellation.cancelRequested()).isTrue();
        LlmAnalysisJob cancelled = lifecycleStore.recoverOneExpiredLease(
                NOW.plus(Duration.ofMinutes(6)),
                NOW.plus(Duration.ofMinutes(5)).plusSeconds(1)
        ).orElseThrow();
        assertThat(cancelled.status()).isEqualTo(LlmAnalysisJobStatus.CANCELLED);
        assertThat(cancelled.leaseOwner()).isNull();

        Instant retryStart = NOW.plus(Duration.ofMinutes(10));
        UUID retrySnapshot = createSnapshot(retryStart);
        LlmAnalysisJob retryQueued = jobStore.enqueue(
                request(retrySnapshot, retryStart.plus(Duration.ofMinutes(5))),
                retryStart
        ).job();
        claimStore.claimNext("worker-3", Duration.ofSeconds(10), retryStart)
                .orElseThrow();
        LlmAnalysisJob waiting = lifecycleStore.recoverOneExpiredLease(
                retryStart.plusSeconds(40),
                retryStart.plusSeconds(11)
        ).orElseThrow();
        assertThat(waiting.id()).isEqualTo(retryQueued.id());
        assertThat(waiting.status()).isEqualTo(LlmAnalysisJobStatus.WAITING_RETRY);
        assertThat(waiting.errorSummary()).contains("scheduled for recovery");

        LlmAnalysisJob reclaimed = claimStore.claimNext(
                "worker-4", Duration.ofMinutes(10), retryStart.plusSeconds(40)
        ).orElseThrow();
        assertThat(reclaimed.attemptCount()).isEqualTo(2);
        LlmAnalysisJob failed = lifecycleStore.recoverOneExpiredLease(
                retryStart.plus(Duration.ofMinutes(6)),
                retryStart.plus(Duration.ofMinutes(5)).plusSeconds(1)
        ).orElseThrow();
        assertThat(failed.status()).isEqualTo(LlmAnalysisJobStatus.FAILED);
        assertThat(failed.terminalReasonCode())
                .isEqualTo(LlmAnalysisJobLifecycleStore.DEADLINE_EXCEEDED);

        Instant expiredStart = NOW.plus(Duration.ofMinutes(20));
        UUID expiredSnapshot = createSnapshot(expiredStart);
        LlmAnalysisJob expiredQueued = jobStore.enqueue(
                request(expiredSnapshot, expiredStart.plus(Duration.ofMinutes(5))),
                expiredStart
        ).job();
        LlmAnalysisJob skipped = lifecycleStore.expireOnePastDeadline(
                expiredStart.plus(Duration.ofMinutes(5))
        ).orElseThrow();
        assertThat(skipped.id()).isEqualTo(expiredQueued.id());
        assertThat(skipped.status()).isEqualTo(LlmAnalysisJobStatus.SKIPPED);
        assertThat(skipped.terminalReasonCode())
                .isEqualTo(LlmAnalysisJobLifecycleStore.DEADLINE_EXCEEDED);
    }

    private Optional<LlmAnalysisJob> concurrentClaim(
            String owner,
            CountDownLatch ready,
            CountDownLatch start
    ) throws Exception {
        ready.countDown();
        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
        return claimStore.claimNext(owner, Duration.ofMinutes(10), NOW);
    }

    private LlmAnalysisJobRequest request(UUID snapshotId, Instant deadlineAt) {
        return new LlmAnalysisJobRequest(
                snapshotId,
                1,
                LlmAnalysisTriggerType.INITIAL,
                null,
                "YANDEX",
                "gpt://folder/yandexgpt/latest",
                "yandex-foundation-models-v1",
                1,
                "weekly-interpretation-v1",
                "weekly-analysis-v1",
                "weekly-budget-v1",
                "{\"maxOutputTokens\":4000,\"maxProviderCalls\":2,\"temperature\":0.2}",
                "a".repeat(64),
                1,
                1,
                deadlineAt
        );
    }

    private UUID createSnapshot(Instant timestamp) {
        UUID connectionId = jdbcTemplate.queryForObject(
                "SELECT id FROM integration_connections WHERE connection_key = ?",
                UUID.class,
                "livesklad-default"
        );
        UUID storeId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO stores (
                    id, connection_id, source_system, external_id, name, timezone
                ) VALUES (?, ?, 'LIVESKLAD', ?, 'LLM lifecycle store', 'Europe/Kaliningrad')
                """,
                storeId,
                connectionId,
                "llm-lifecycle-" + storeId
        );
        UUID syncJobId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO sync_jobs (
                    id, connection_id, job_type, status, phase, period_start, period_end,
                    cursor_start, current_window_end, window_size_minutes, max_attempts,
                    next_attempt_at, started_at, finished_at
                ) VALUES (
                    ?, ?, 'INCREMENTAL', 'SUCCESS', 'RETURNS', ?, ?, ?, ?, 1440, 5,
                    ?, ?, ?
                )
                """,
                syncJobId,
                connectionId,
                Timestamp.from(timestamp.minus(Duration.ofDays(7))),
                Timestamp.from(timestamp),
                Timestamp.from(timestamp),
                Timestamp.from(timestamp),
                Timestamp.from(timestamp),
                Timestamp.from(timestamp.minusSeconds(60)),
                Timestamp.from(timestamp)
        );
        UUID snapshotId = UUID.randomUUID();
        LocalDate start = LocalDate.of(2026, 7, 27);
        jdbcTemplate.update(
                """
                INSERT INTO analytics_snapshots (
                    id, store_id, period_start, period_end, timezone, revision,
                    revision_reason_code, source_sync_job_id, source_sync_completed_at,
                    source_data_cutoff, facts_schema_version, metrics_contract_version,
                    calculation_version, quality_policy_version, quality_status,
                    facts_payload, facts_hash, created_at
                ) VALUES (
                    ?, ?, ?, ?, 'Europe/Kaliningrad', 1, 'INITIAL', ?, ?, ?, 1,
                    'weekly-metrics-v1', 'weekly-snapshot-v1', 'weekly-quality-v1',
                    'READY', '{}'::jsonb, ?, ?
                )
                """,
                snapshotId,
                storeId,
                start,
                start.plusDays(6),
                syncJobId,
                Timestamp.from(timestamp),
                Timestamp.from(timestamp),
                "b".repeat(64),
                Timestamp.from(timestamp)
        );
        return snapshotId;
    }
}
