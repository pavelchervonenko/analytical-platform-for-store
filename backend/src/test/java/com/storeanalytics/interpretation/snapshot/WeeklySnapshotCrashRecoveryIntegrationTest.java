package com.storeanalytics.interpretation.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Facts;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Manifest;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.QualityStatus;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import java.sql.Timestamp;
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
class WeeklySnapshotCrashRecoveryIntegrationTest {

    private static final Instant SYNC_AT = Instant.parse("2026-07-27T03:00:00Z");
    private static final StoreKpiPeriod PERIOD = new StoreKpiPeriod(
            LocalDate.of(2026, 7, 20),
            LocalDate.of(2026, 7, 26)
    );

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private WeeklySnapshotStore snapshotStore;

    @Autowired
    private WeeklySnapshotJobStore jobStore;

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
    void repeatingSamePersistenceCommandRecoversCreatedOutcome() {
        TestGraph graph = createGraph();
        WeeklySnapshotPersistenceCommand command = command(graph);

        WeeklySnapshotWriteResult first = snapshotStore.persist(command);
        WeeklySnapshotWriteResult recovered = snapshotStore.persist(command);

        assertThat(first.outcome()).isEqualTo(WeeklySnapshotWriteOutcome.CREATED);
        assertThat(recovered.outcome()).isEqualTo(WeeklySnapshotWriteOutcome.CREATED);
        assertThat(recovered.snapshot().id()).isEqualTo(first.snapshot().id());
        assertThat(recovered.snapshot().revision()).isEqualTo(1);
        assertThat(snapshotCount(graph.storeId())).isEqualTo(1);

        WeeklySnapshotJobRequest invalidInitial = new WeeklySnapshotJobRequest(
                graph.storeId(),
                null,
                WeeklySnapshotJobType.INITIAL,
                PERIOD,
                "Europe/Moscow",
                graph.syncJobId(),
                SYNC_AT,
                WeeklySnapshotPolicyV1.VERSIONS,
                null,
                3
        );
        assertThatThrownBy(() -> jobStore.enqueue(invalidInitial, SYNC_AT))
                .isInstanceOf(WeeklySnapshotJobConflictException.class)
                .hasMessageContaining("already exists");
    }

    private WeeklySnapshotPersistenceCommand command(TestGraph graph) {
        WeeklySnapshotPayload payload = new WeeklySnapshotPayload(
                1,
                new Manifest(List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
                new Facts(List.of(), List.of(), List.of(), List.of())
        );
        WeeklyAnalyticsFactsQuery query = new WeeklyAnalyticsFactsQuery(
                graph.storeId(),
                PERIOD,
                new StoreKpiPeriod(
                        PERIOD.start().minusDays(7),
                        PERIOD.end().minusDays(7)
                )
        );
        WeeklySnapshotDraft draft = new WeeklySnapshotDraft(
                graph.storeId(),
                query,
                "Europe/Moscow",
                QualityStatus.READY,
                WeeklySnapshotPolicyV1.VERSIONS,
                List.of(),
                payload,
                codec.hash(payload, List.of())
        );
        return new WeeklySnapshotPersistenceCommand(
                draft,
                graph.syncJobId(),
                SYNC_AT,
                SYNC_AT,
                WeeklySnapshotRevisionReason.AUTO_REVISION,
                null
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
                ) VALUES (?, ?, 'LIVESKLAD', ?, 'Snapshot crash recovery store')
                """,
                storeId,
                connectionId,
                "snapshot-crash-recovery-" + storeId
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
                Timestamp.from(SYNC_AT.minusSeconds(604_800)),
                Timestamp.from(SYNC_AT),
                Timestamp.from(SYNC_AT),
                Timestamp.from(SYNC_AT),
                Timestamp.from(SYNC_AT),
                Timestamp.from(SYNC_AT.minusSeconds(60)),
                Timestamp.from(SYNC_AT)
        );
        return new TestGraph(storeId, syncJobId);
    }

    private int snapshotCount(UUID storeId) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM analytics_snapshots WHERE store_id = ?",
                Integer.class,
                storeId
        );
        return value == null ? 0 : value;
    }

    private record TestGraph(UUID storeId, UUID syncJobId) {
    }
}
