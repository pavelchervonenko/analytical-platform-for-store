package com.storeanalytics.interpretation.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.interpretation.config.WeeklySnapshotPlannerProperties;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
class WeeklySnapshotPlanningServiceIntegrationTest {

    private static final LocalDate PERIOD_START = LocalDate.of(2026, 7, 27);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 8, 2);
    private static final Instant REQUIRED_COVERAGE = Instant.parse(
            "2026-08-02T22:00:00Z"
    );
    private static final Instant MONDAY_NOW = Instant.parse("2026-08-03T02:00:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private WeeklySnapshotPlanningStore planningStore;

    @Autowired
    private WeeklySnapshotJobStore jobStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void concurrentReconciliationCreatesOneInitialJobFromNewestSuitableSync()
            throws Exception {
        TestStore store = createStore();
        UUID suitableSource = addSuccessfulSync(
                store.connectionId(),
                REQUIRED_COVERAGE,
                MONDAY_NOW.minusSeconds(60)
        );
        addSuccessfulSync(
                store.connectionId(),
                REQUIRED_COVERAGE.minus(Duration.ofDays(1)),
                MONDAY_NOW.minusSeconds(10)
        );
        WeeklySnapshotPlanningService service = service(MONDAY_NOW);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<WeeklySnapshotPlanningResult> first = executor.submit(() ->
                    concurrentPlan(service, ready, start));
            Future<WeeklySnapshotPlanningResult> second = executor.submit(() ->
                    concurrentPlan(service, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }

        assertThat(jobCount(store.storeId())).isOne();
        JobRow job = job(store.storeId());
        assertThat(job.type()).isEqualTo("INITIAL");
        assertThat(job.periodStart()).isEqualTo(PERIOD_START);
        assertThat(job.periodEnd()).isEqualTo(PERIOD_END);
        assertThat(job.sourceSyncJobId()).isEqualTo(suitableSource);
        assertThat(job.sourceDataCutoff()).isEqualTo(MONDAY_NOW.minusSeconds(60));

        service.plan();
        assertThat(jobCount(store.storeId())).isOne();
    }

    @Test
    void createsRevisionForNewCutoffInsideWindowButNotAfterDeadline() {
        TestStore store = createStore();
        Instant baseCompletedAt = MONDAY_NOW.minus(Duration.ofHours(1));
        UUID baseSource = addSuccessfulSync(
                store.connectionId(), REQUIRED_COVERAGE, baseCompletedAt
        );
        UUID baseSnapshot = addBaseSnapshot(store.storeId(), baseSource, baseCompletedAt);
        Instant tuesdayNow = Instant.parse("2026-08-04T02:00:00Z");
        UUID revisionSource = addSuccessfulSync(
                store.connectionId(),
                REQUIRED_COVERAGE.plus(Duration.ofDays(1)),
                tuesdayNow.minusSeconds(60)
        );

        service(tuesdayNow).plan();

        assertThat(jobCount(store.storeId())).isOne();
        JobRow revision = job(store.storeId());
        assertThat(revision.type()).isEqualTo("AUTO_REVISION");
        assertThat(revision.baseSnapshotId()).isEqualTo(baseSnapshot);
        assertThat(revision.sourceSyncJobId()).isEqualTo(revisionSource);

        Instant afterDeadline = Instant.parse("2026-08-06T02:00:00Z");
        addSuccessfulSync(
                store.connectionId(),
                REQUIRED_COVERAGE.plus(Duration.ofDays(3)),
                afterDeadline.minusSeconds(60)
        );
        service(afterDeadline).plan();

        assertThat(jobCount(store.storeId())).isOne();
    }

    private WeeklySnapshotPlanningResult concurrentPlan(
            WeeklySnapshotPlanningService service,
            CountDownLatch ready,
            CountDownLatch start
    ) throws Exception {
        ready.countDown();
        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
        return service.plan();
    }

    private WeeklySnapshotPlanningService service(Instant now) {
        return new WeeklySnapshotPlanningService(
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
    }

    private TestStore createStore() {
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
                ) VALUES (?, ?, 'LIVESKLAD', ?, 'Planner test store', ?)
                """,
                storeId,
                connectionId,
                "snapshot-planner-" + storeId,
                "Europe/Kaliningrad"
        );
        return new TestStore(storeId, connectionId);
    }

    private UUID addSuccessfulSync(
            UUID connectionId,
            Instant periodEnd,
            Instant completedAt
    ) {
        UUID id = UUID.randomUUID();
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
                id,
                connectionId,
                Timestamp.from(periodEnd.minus(Duration.ofDays(3))),
                Timestamp.from(periodEnd),
                Timestamp.from(periodEnd),
                Timestamp.from(periodEnd),
                Timestamp.from(completedAt),
                Timestamp.from(completedAt.minusSeconds(60)),
                Timestamp.from(completedAt)
        );
        return id;
    }

    private UUID addBaseSnapshot(
            UUID storeId,
            UUID sourceSyncJobId,
            Instant completedAt
    ) {
        UUID id = UUID.randomUUID();
        var versions = WeeklySnapshotPolicyV1.VERSIONS;
        jdbcTemplate.update(
                """
                INSERT INTO analytics_snapshots (
                    id, store_id, snapshot_type, period_start, period_end, timezone,
                    revision, revision_reason_code, source_sync_job_id,
                    source_sync_completed_at, source_data_cutoff, facts_schema_version,
                    metrics_contract_version, calculation_version,
                    quality_policy_version, quality_status, facts_payload, facts_hash
                ) VALUES (
                    ?, ?, 'WEEKLY', ?, ?, 'Europe/Kaliningrad', 1, 'INITIAL',
                    ?, ?, ?, ?, ?, ?, ?, 'READY', CAST(? AS jsonb), ?
                )
                """,
                id,
                storeId,
                PERIOD_START,
                PERIOD_END,
                sourceSyncJobId,
                Timestamp.from(completedAt),
                Timestamp.from(completedAt),
                versions.factsSchemaVersion(),
                versions.metricContractVersion(),
                versions.calculationVersion(),
                versions.qualityPolicyVersion(),
                "{}",
                "a".repeat(64)
        );
        return id;
    }

    private long jobCount(UUID storeId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM analytics_snapshot_jobs WHERE store_id = ?",
                Long.class,
                storeId
        );
    }

    private JobRow job(UUID storeId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT job_type, period_start, period_end, source_sync_job_id,
                       source_data_cutoff, base_snapshot_id
                FROM analytics_snapshot_jobs
                WHERE store_id = ?
                """,
                (resultSet, rowNumber) -> new JobRow(
                        resultSet.getString("job_type"),
                        resultSet.getObject("period_start", LocalDate.class),
                        resultSet.getObject("period_end", LocalDate.class),
                        resultSet.getObject("source_sync_job_id", UUID.class),
                        resultSet.getTimestamp("source_data_cutoff").toInstant(),
                        resultSet.getObject("base_snapshot_id", UUID.class)
                ),
                storeId
        );
    }

    private record TestStore(UUID storeId, UUID connectionId) {
    }

    private record JobRow(
            String type,
            LocalDate periodStart,
            LocalDate periodEnd,
            UUID sourceSyncJobId,
            Instant sourceDataCutoff,
            UUID baseSnapshotId
    ) {
    }
}
