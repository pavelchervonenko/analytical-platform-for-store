package com.storeanalytics.interpretation.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.metrics.service.StoreKpiPeriod;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
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
class WeeklySnapshotJobLifecycleStoreIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-27T04:00:00Z");
    private static final StoreKpiPeriod PERIOD = new StoreKpiPeriod(
            LocalDate.of(2026, 7, 20),
            LocalDate.of(2026, 7, 26)
    );

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private WeeklySnapshotJobStore jobStore;

    @Autowired
    private WeeklySnapshotJobLifecycleStore lifecycleStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void heartbeatRequiresLiveOwnedLeaseAndRunningCancellationIsCooperative() {
        TestGraph graph = createGraph();
        WeeklySnapshotJob queued = jobStore.enqueue(request(graph, 3), NOW);
        WeeklySnapshotJob claimed = jobStore.claimNext(
                "worker-1", Duration.ofMinutes(1), NOW
        ).orElseThrow();

        WeeklySnapshotJob heartbeat = lifecycleStore.heartbeat(
                claimed.id(),
                "worker-1",
                Duration.ofMinutes(2),
                NOW.plusSeconds(30)
        );

        assertThat(heartbeat.leaseUntil()).isEqualTo(NOW.plusSeconds(150));
        assertThat(heartbeat.version()).isEqualTo(claimed.version() + 1);
        assertThatThrownBy(() -> lifecycleStore.heartbeat(
                claimed.id(),
                "worker-2",
                Duration.ofMinutes(2),
                NOW.plusSeconds(31)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("owned elsewhere");

        WeeklySnapshotJob cancellation = lifecycleStore.requestCancellation(
                claimed.id(),
                NOW.plusSeconds(40)
        );
        assertThat(cancellation.status()).isEqualTo(WeeklySnapshotJobStatus.RUNNING);
        assertThat(cancellation.cancelRequested()).isTrue();

        WeeklySnapshotJob cancelled = jobStore.retryOrFail(
                queued.id(),
                "worker-1",
                true,
                "TRANSIENT_DATABASE",
                "Weekly snapshot execution failed: TRANSIENT_DATABASE",
                NOW.plusSeconds(60),
                NOW.plusSeconds(41)
        );
        assertThat(cancelled.status()).isEqualTo(WeeklySnapshotJobStatus.CANCELLED);
        assertThat(cancelled.leaseOwner()).isNull();
        assertThat(cancelled.errorCode()).isNull();
        assertThat(cancelled.errorSummary()).isNull();
        assertThat(cancelled.finishedAt()).isEqualTo(NOW.plusSeconds(41));
    }

    @Test
    void expiredLeaseRetriesOnceThenFailsAtAttemptLimit() {
        TestGraph graph = createGraph();
        WeeklySnapshotJob queued = jobStore.enqueue(request(graph, 2), NOW);
        jobStore.claimNext("worker-1", Duration.ofSeconds(10), NOW).orElseThrow();
        Instant firstRecoveryAt = NOW.plusSeconds(11);

        assertThat(lifecycleStore.countExpiredLeases(firstRecoveryAt)).isEqualTo(1);
        WeeklySnapshotJob waiting = lifecycleStore.recoverOneExpiredLease(
                firstRecoveryAt.plusSeconds(30),
                firstRecoveryAt
        ).orElseThrow();

        assertThat(waiting.id()).isEqualTo(queued.id());
        assertThat(waiting.status()).isEqualTo(WeeklySnapshotJobStatus.WAITING_RETRY);
        assertThat(waiting.errorCode())
                .isEqualTo(WeeklySnapshotJobLifecycleStore.EXPIRED_LEASE_ERROR_CODE);
        assertThat(waiting.leaseOwner()).isNull();

        Instant secondClaimAt = firstRecoveryAt.plusSeconds(30);
        WeeklySnapshotJob secondClaim = jobStore.claimNext(
                "worker-2", Duration.ofSeconds(10), secondClaimAt
        ).orElseThrow();
        assertThat(secondClaim.attemptCount()).isEqualTo(2);
        Instant secondRecoveryAt = secondClaimAt.plusSeconds(11);
        WeeklySnapshotJob failed = lifecycleStore.recoverOneExpiredLease(
                secondRecoveryAt.plusSeconds(30),
                secondRecoveryAt
        ).orElseThrow();

        assertThat(failed.status()).isEqualTo(WeeklySnapshotJobStatus.FAILED);
        assertThat(failed.finishedAt()).isEqualTo(secondRecoveryAt);
        assertThat(lifecycleStore.countByStatus(WeeklySnapshotJobStatus.FAILED))
                .isEqualTo(1);
    }

    @Test
    void cancellationImmediatelyClosesPendingJobAndIsIdempotent() {
        TestGraph graph = createGraph();
        WeeklySnapshotJob queued = jobStore.enqueue(request(graph, 3), NOW);

        WeeklySnapshotJob cancelled = lifecycleStore.requestCancellation(
                queued.id(),
                NOW.plusSeconds(1)
        );
        WeeklySnapshotJob repeated = lifecycleStore.requestCancellation(
                queued.id(),
                NOW.plusSeconds(2)
        );

        assertThat(cancelled.status()).isEqualTo(WeeklySnapshotJobStatus.CANCELLED);
        assertThat(cancelled.cancelRequested()).isTrue();
        assertThat(repeated.version()).isEqualTo(cancelled.version());
        assertThat(jobStore.claimNext(
                "worker", Duration.ofMinutes(1), NOW.plusSeconds(3)
        )).isEmpty();
    }

    private WeeklySnapshotJobRequest request(TestGraph graph, int maxAttempts) {
        return new WeeklySnapshotJobRequest(
                graph.storeId(),
                null,
                WeeklySnapshotJobType.INITIAL,
                PERIOD,
                "Europe/Moscow",
                graph.syncJobId(),
                NOW,
                WeeklySnapshotPolicyV1.VERSIONS,
                null,
                maxAttempts
        );
    }

    private TestGraph createGraph() {
        UUID connectionId = jdbcTemplate.queryForObject(
                "SELECT id FROM integration_connections WHERE connection_key = 'livesklad-default'",
                UUID.class
        );
        UUID storeId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO stores (
                    id, connection_id, source_system, external_id, name
                ) VALUES (?, ?, 'LIVESKLAD', ?, 'Snapshot lifecycle test store')
                """,
                storeId,
                connectionId,
                "snapshot-lifecycle-" + storeId
        );
        UUID syncJobId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO sync_jobs (
                    id, connection_id, job_type, status, phase, period_start, period_end,
                    cursor_start, current_window_end, window_size_minutes, max_attempts,
                    next_attempt_at, started_at, finished_at
                ) VALUES (
                    ?, ?, 'BACKFILL', 'SUCCESS', 'RETURNS', ?, ?, ?, ?, 1440, 3, ?, ?, ?
                )
                """,
                syncJobId,
                connectionId,
                Timestamp.from(NOW.minusSeconds(604_800)),
                Timestamp.from(NOW),
                Timestamp.from(NOW),
                Timestamp.from(NOW),
                Timestamp.from(NOW),
                Timestamp.from(NOW.minusSeconds(60)),
                Timestamp.from(NOW)
        );
        return new TestGraph(storeId, syncJobId);
    }

    private record TestGraph(UUID storeId, UUID syncJobId) {
    }
}
