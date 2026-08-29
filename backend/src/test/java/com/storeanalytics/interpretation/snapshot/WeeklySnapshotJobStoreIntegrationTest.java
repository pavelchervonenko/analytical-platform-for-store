package com.storeanalytics.interpretation.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Facts;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Manifest;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.QualityStatus;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
class WeeklySnapshotJobStoreIntegrationTest {

    private static final LocalDate PERIOD_START = LocalDate.of(2026, 7, 20);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 7, 26);
    private static final Instant NOW = Instant.parse("2026-07-27T03:00:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private WeeklySnapshotJobStore jobStore;

    @Autowired
    private WeeklySnapshotStore snapshotStore;

    @Autowired
    private WeeklySnapshotPayloadCodec codec;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void enqueueIsIdempotentAndClaimUsesLeaseWithRetryLimit() {
        TestGraph graph = createGraph();
        WeeklySnapshotJobRequest request = request(
                graph, graph.firstSyncJobId(), WeeklySnapshotJobType.INITIAL, null, 2
        );

        WeeklySnapshotJob queued = jobStore.enqueue(request, NOW);
        WeeklySnapshotJob duplicate = jobStore.enqueue(request, NOW.plusSeconds(1));

        assertThat(duplicate.id()).isEqualTo(queued.id());
        assertThatThrownBy(() -> jobStore.enqueue(
                request(graph, graph.secondSyncJobId(),
                        WeeklySnapshotJobType.INITIAL, null, 2),
                NOW.plusSeconds(2)
        )).isInstanceOf(WeeklySnapshotJobConflictException.class)
                .hasMessageContaining("active weekly snapshot job");

        WeeklySnapshotJob firstClaim = jobStore.claimNext(
                "snapshot-worker-1", Duration.ofMinutes(2), NOW
        ).orElseThrow();
        assertThat(firstClaim.status()).isEqualTo(WeeklySnapshotJobStatus.RUNNING);
        assertThat(firstClaim.attemptCount()).isEqualTo(1);
        assertThat(firstClaim.leaseOwner()).isEqualTo("snapshot-worker-1");

        Instant retryAt = NOW.plusSeconds(30);
        WeeklySnapshotJob waiting = jobStore.retryOrFail(
                firstClaim.id(), "snapshot-worker-1", true,
                "SOURCE_NOT_READY", "Source data is not ready", retryAt, NOW.plusSeconds(1)
        );
        assertThat(waiting.status()).isEqualTo(WeeklySnapshotJobStatus.WAITING_RETRY);
        assertThat(jobStore.claimNext(
                "snapshot-worker-2", Duration.ofMinutes(2), retryAt.minusSeconds(1)
        )).isEmpty();

        WeeklySnapshotJob secondClaim = jobStore.claimNext(
                "snapshot-worker-2", Duration.ofMinutes(2), retryAt
        ).orElseThrow();
        assertThat(secondClaim.attemptCount()).isEqualTo(2);
        WeeklySnapshotJob failed = jobStore.retryOrFail(
                secondClaim.id(), "snapshot-worker-2", true,
                "SOURCE_NOT_READY", "Still not ready", retryAt.plusSeconds(30), retryAt
        );
        assertThat(failed.status()).isEqualTo(WeeklySnapshotJobStatus.FAILED);
        assertThat(failed.finishedAt()).isEqualTo(retryAt);
        assertThat(jobStore.claimNext(
                "snapshot-worker-3", Duration.ofMinutes(2), retryAt.plusSeconds(60)
        )).isEmpty();
    }

    @Test
    void terminalFailureClampsFinishedAtWhenClockMovesBackwards() {
        TestGraph graph = createGraph();
        WeeklySnapshotJob queued = jobStore.enqueue(
                request(
                        graph, graph.firstSyncJobId(), WeeklySnapshotJobType.INITIAL,
                        null, 1
                ),
                NOW
        );
        WeeklySnapshotJob claimed = jobStore.claimNext(
                "snapshot-worker", Duration.ofMinutes(2), NOW
        ).orElseThrow();

        WeeklySnapshotJob failed = jobStore.retryOrFail(
                claimed.id(),
                "snapshot-worker",
                false,
                "SNAPSHOT_EXECUTION",
                "Weekly snapshot execution failed: SNAPSHOT_EXECUTION",
                NOW.plusSeconds(30),
                NOW.minusMillis(1)
        );

        assertThat(queued.id()).isEqualTo(claimed.id());
        assertThat(failed.status()).isEqualTo(WeeklySnapshotJobStatus.FAILED);
        assertThat(failed.finishedAt()).isEqualTo(NOW);
    }

    @Test
    void autoRevisionCanFinishUnchangedWhenClockMovesBackwards() {
        TestGraph graph = createGraph();
        WeeklySnapshotDraft draft = draft(graph.storeId());
        WeeklySnapshotWriteResult base = snapshotStore.persist(
                new WeeklySnapshotPersistenceCommand(
                        draft,
                        graph.firstSyncJobId(),
                        NOW.minusSeconds(60),
                        NOW.minusSeconds(60),
                        WeeklySnapshotRevisionReason.AUTO_REVISION,
                        null
                )
        );
        WeeklySnapshotJob queued = jobStore.enqueue(
                request(
                        graph, graph.secondSyncJobId(), WeeklySnapshotJobType.AUTO_REVISION,
                        base.snapshot().id(), 3
                ),
                NOW
        );
        WeeklySnapshotJob claimed = jobStore.claimNext(
                "snapshot-worker", Duration.ofMinutes(2), NOW
        ).orElseThrow();

        WeeklySnapshotJob completed = jobStore.complete(
                claimed.id(),
                "snapshot-worker",
                new WeeklySnapshotWriteResult(
                        WeeklySnapshotWriteOutcome.UNCHANGED,
                        base.snapshot()
                ),
                NOW.minusMillis(1)
        );

        assertThat(queued.id()).isEqualTo(claimed.id());
        assertThat(completed.status()).isEqualTo(WeeklySnapshotJobStatus.SUCCESS);
        assertThat(completed.outcome()).isEqualTo(WeeklySnapshotWriteOutcome.UNCHANGED);
        assertThat(completed.resultSnapshotId()).isEqualTo(base.snapshot().id());
        assertThat(completed.leaseOwner()).isNull();
        assertThat(completed.finishedAt()).isEqualTo(NOW);
    }

    private WeeklySnapshotJobRequest request(
            TestGraph graph,
            UUID syncJobId,
            WeeklySnapshotJobType type,
            UUID baseSnapshotId,
            int maxAttempts
    ) {
        return new WeeklySnapshotJobRequest(
                graph.storeId(), null, type, period(), "Europe/Moscow", syncJobId,
                NOW, WeeklySnapshotPolicyV1.VERSIONS, baseSnapshotId, maxAttempts
        );
    }

    private WeeklySnapshotDraft draft(UUID storeId) {
        WeeklySnapshotPayload payload = new WeeklySnapshotPayload(
                1,
                new Manifest(List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
                new Facts(List.of(), List.of(), List.of(), List.of())
        );
        WeeklyAnalyticsFactsQuery query = new WeeklyAnalyticsFactsQuery(
                storeId,
                period(),
                new StoreKpiPeriod(PERIOD_START.minusDays(7), PERIOD_END.minusDays(7))
        );
        return new WeeklySnapshotDraft(
                storeId, query, "Europe/Moscow", QualityStatus.READY,
                WeeklySnapshotPolicyV1.VERSIONS, List.of(), payload,
                codec.hash(payload, List.of())
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
                ) VALUES (?, ?, 'LIVESKLAD', ?, 'Snapshot job test store')
                """,
                storeId, connectionId, "snapshot-job-store-" + storeId
        );
        UUID firstSync = addSuccessfulSync(connectionId, NOW.minusSeconds(60));
        UUID secondSync = addSuccessfulSync(connectionId, NOW);
        return new TestGraph(storeId, firstSync, secondSync);
    }

    private UUID addSuccessfulSync(UUID connectionId, Instant completedAt) {
        UUID id = UUID.randomUUID();
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
                id, connectionId,
                Timestamp.from(completedAt.minusSeconds(604_800)),
                Timestamp.from(completedAt),
                Timestamp.from(completedAt),
                Timestamp.from(completedAt),
                Timestamp.from(completedAt),
                Timestamp.from(completedAt.minusSeconds(60)),
                Timestamp.from(completedAt)
        );
        return id;
    }

    private StoreKpiPeriod period() {
        return new StoreKpiPeriod(PERIOD_START, PERIOD_END);
    }

    private record TestGraph(
            UUID storeId,
            UUID firstSyncJobId,
            UUID secondSyncJobId
    ) {
    }
}
