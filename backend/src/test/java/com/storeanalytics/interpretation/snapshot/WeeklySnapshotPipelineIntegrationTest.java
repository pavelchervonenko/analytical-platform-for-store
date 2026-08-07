package com.storeanalytics.interpretation.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.interpretation.config.WeeklySnapshotPlannerProperties;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
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
class WeeklySnapshotPipelineIntegrationTest {

    private static final ZoneId STORE_ZONE = ZoneId.of("Europe/Kaliningrad");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private WeeklySnapshotPlanningStore planningStore;

    @Autowired
    private WeeklySnapshotJobStore jobStore;

    @Autowired
    private WeeklySnapshotJobCoordinator coordinator;

    @Autowired
    private WeeklySnapshotStore snapshotStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void plannerToWorkerCreatesImmutableSnapshotAndCompletesJob() {
        Instant now = Instant.now().minusSeconds(5).truncatedTo(ChronoUnit.MILLIS);
        LocalDate currentWeekStart = LocalDate.ofInstant(now, STORE_ZONE)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        StoreKpiPeriod period = new StoreKpiPeriod(
                currentWeekStart.minusWeeks(1),
                currentWeekStart.minusDays(1)
        );
        Instant requiredCoverage = currentWeekStart.atStartOfDay(STORE_ZONE).toInstant();
        TestGraph graph = createGraph(requiredCoverage, now.minusSeconds(60));
        WeeklySnapshotPlanningService planner = new WeeklySnapshotPlanningService(
                planningStore,
                jobStore,
                new WeeklySnapshotPlannerProperties(
                        true,
                        Duration.ofMinutes(1),
                        Duration.ofHours(72),
                        100,
                        5
                ),
                Clock.fixed(now, ZoneOffset.UTC)
        );

        WeeklySnapshotPlanningResult planned = planner.plan();
        WeeklySnapshotJob terminal = coordinator.runNext(
                "pipeline-integration-worker",
                Duration.ofMinutes(5),
                Duration.ofSeconds(30),
                Duration.ofMinutes(5)
        ).orElseThrow();

        assertThat(planned.requestsAccepted()).isOne();
        assertThat(terminal.status()).isEqualTo(WeeklySnapshotJobStatus.SUCCESS);
        assertThat(terminal.outcome()).isEqualTo(WeeklySnapshotWriteOutcome.CREATED);
        PersistedWeeklySnapshot snapshot = snapshotStore.findLatest(
                graph.storeId(), period
        ).orElseThrow();
        assertThat(snapshot.id()).isEqualTo(terminal.resultSnapshotId());
        assertThat(snapshot.revision()).isOne();
        assertThat(snapshot.sourceSyncJobId()).isEqualTo(graph.syncJobId());
        assertThat(snapshot.timezone()).isEqualTo(STORE_ZONE.getId());
        assertThat(snapshot.query().period()).isEqualTo(period);
        assertThat(snapshot.payload().facts()).isNotNull();
        assertThat(snapshotCount(graph.storeId())).isOne();
        assertThat(llmAnalysisJobCount()).isZero();
    }

    private TestGraph createGraph(Instant periodEnd, Instant completedAt) {
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
                ) VALUES (?, ?, 'LIVESKLAD', ?, 'Pipeline integration store', ?)
                """,
                storeId,
                connectionId,
                "snapshot-pipeline-" + storeId,
                STORE_ZONE.getId()
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
                Timestamp.from(periodEnd.minus(Duration.ofDays(3))),
                Timestamp.from(periodEnd),
                Timestamp.from(periodEnd),
                Timestamp.from(periodEnd),
                Timestamp.from(completedAt),
                Timestamp.from(completedAt.minusSeconds(60)),
                Timestamp.from(completedAt)
        );
        return new TestGraph(storeId, syncJobId);
    }

    private long snapshotCount(UUID storeId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM analytics_snapshots WHERE store_id = ?",
                Long.class,
                storeId
        );
    }

    private long llmAnalysisJobCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM llm_analysis_jobs",
                Long.class
        );
    }

    private record TestGraph(UUID storeId, UUID syncJobId) {
    }
}
