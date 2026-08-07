package com.storeanalytics.interpretation.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

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
class WeeklySnapshotJobControlStoreIntegrationTest {

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
    private WeeklySnapshotJobControlStore controlStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void heartbeatRenewsOnlyLiveJobOwnedByWorkerAndCancellationIsVisible() {
        TestGraph graph = createGraph();
        WeeklySnapshotJob queued = jobStore.enqueue(request(graph), NOW);
        WeeklySnapshotJob claimed = jobStore.claimNext(
                "worker-1",
                Duration.ofMinutes(1),
                NOW
        ).orElseThrow();

        assertThat(controlStore.heartbeatOwned(
                "worker-2",
                Duration.ofMinutes(2),
                NOW.plusSeconds(30)
        )).isEmpty();
        assertThat(controlStore.heartbeatOwned(
                "worker-1",
                Duration.ofMinutes(2),
                NOW.plusSeconds(30)
        )).contains(claimed.id());
        assertThat(jobStore.findById(claimed.id()).orElseThrow().leaseUntil())
                .isEqualTo(NOW.plusSeconds(150));

        lifecycleStore.requestCancellation(queued.id(), NOW.plusSeconds(40));
        assertThat(controlStore.cancellationRequested(queued.id())).isTrue();
    }

    @Test
    void heartbeatCannotReviveExpiredLease() {
        TestGraph graph = createGraph();
        WeeklySnapshotJob queued = jobStore.enqueue(request(graph), NOW);
        jobStore.claimNext("worker", Duration.ofSeconds(10), NOW).orElseThrow();

        assertThat(controlStore.heartbeatOwned(
                "worker",
                Duration.ofMinutes(1),
                NOW.plusSeconds(11)
        )).isEmpty();
        assertThat(jobStore.findById(queued.id()).orElseThrow().leaseUntil())
                .isEqualTo(NOW.plusSeconds(10));
    }

    private WeeklySnapshotJobRequest request(TestGraph graph) {
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
                3
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
                ) VALUES (?, ?, 'LIVESKLAD', ?, 'Snapshot control test store')
                """,
                storeId,
                connectionId,
                "snapshot-control-" + storeId
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
