package com.storeanalytics.interpretation.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Facts;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Manifest;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Versions;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class HistoricalSalesWeeklyCoverageIntegrationTest {

    private static final Instant START = Instant.parse("2026-08-10T00:00:00Z");
    private static final Instant END = Instant.parse("2026-08-24T00:00:00Z");
    private static final Instant FINISHED = END.plusSeconds(60);
    private static final Instant NOW = END.plusSeconds(3600);

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static WeeklySnapshotPlanningStore planning;
    private static WeeklySnapshotJobStore jobs;
    private static WeeklySnapshotSourceSyncReader source;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()
        );
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        planning = new WeeklySnapshotPlanningStore(jdbc);
        jobs = new WeeklySnapshotJobStore(jdbc);
        source = new WeeklySnapshotSourceSyncReader(jdbc);
    }

    @Test
    void historicalOnlyTwoWeekCoverageCannotProvideOrAdmitASnapshotSource() {
        TestStore store = store();
        UUID latest = null;
        for (Instant cursor = START; cursor.isBefore(END); cursor = cursor.plusSeconds(10_800)) {
            latest = successfulJob(store, "HISTORICAL_SALES", cursor,
                    cursor.plusSeconds(10_800), FINISHED);
        }
        UUID historicalSource = latest;

        assertThat(planning.newestSuitableSource(store.id(), END, NOW)).isEmpty();
        assertThat(planning.newestSuitableSource(store.id(), START, END, NOW)).isEmpty();
        assertThatThrownBy(() -> source.completedAt(store.id(), historicalSource))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> jobs.enqueue(request(store, historicalSource), NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM analytics_snapshot_jobs WHERE store_id = ?",
                Integer.class, store.id()
        )).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"BACKFILL", "INCREMENTAL"})
    void fullPhaseSourceRemainsEligibleAfterANewerHistoricalSaleSuccess(String jobType) {
        TestStore store = store();
        UUID fullSource = successfulJob(store, jobType, START, END, FINISHED);
        successfulJob(store, "HISTORICAL_SALES", END.minusSeconds(10_800), END,
                FINISHED.plusSeconds(60));

        assertThat(planning.newestSuitableSource(store.id(), END, NOW))
                .hasValueSatisfying(value -> assertThat(value.syncJobId()).isEqualTo(fullSource));
        assertThat(planning.newestSuitableSource(store.id(), START, END, NOW))
                .hasValueSatisfying(value -> assertThat(value.syncJobId()).isEqualTo(fullSource));
        assertThat(source.completedAt(store.id(), fullSource)).isEqualTo(FINISHED);
        assertThat(jobs.enqueue(request(store, fullSource), NOW).sourceSyncJobId())
                .isEqualTo(fullSource);
    }

    @Test
    void historicalSaleCoverageCannotFillAGapInFullPhaseCoverage() {
        TestStore store = store();
        Instant gap = END.minusSeconds(10_800);
        successfulJob(store, "BACKFILL", START, gap, FINISHED);
        successfulJob(store, "HISTORICAL_SALES", gap, END, FINISHED.plusSeconds(60));

        assertThat(planning.newestSuitableSource(store.id(), START, END, NOW)).isEmpty();
    }

    @Test
    void databaseRejectsAHistoricalSourceEvenWithAValidCanonicalSnapshotBody() {
        TestStore store = store();
        UUID historical = successfulJob(store, "HISTORICAL_SALES", START,
                START.plusSeconds(10_800), FINISHED);

        assertThatThrownBy(() -> insertSnapshot(store, historical))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Analytics snapshot source sync job is inconsistent");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM analytics_snapshots WHERE store_id = ?",
                Integer.class, store.id()
        )).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"BACKFILL", "INCREMENTAL"})
    void databaseAcceptsAFullPhaseSourceWithTheSameCanonicalSnapshotBody(String jobType) {
        TestStore store = store();
        UUID fullSource = successfulJob(store, jobType, START, END, FINISHED);
        UUID snapshotId = insertSnapshot(store, fullSource);

        assertThat(jdbc.queryForObject(
                "SELECT source_sync_job_id FROM analytics_snapshots WHERE id = ?",
                UUID.class, snapshotId
        )).isEqualTo(fullSource);
    }

    private UUID insertSnapshot(TestStore store, UUID sourceId) {
        UUID id = UUID.randomUUID();
        WeeklySnapshotPayload payload = new WeeklySnapshotPayload(
                1,
                new Manifest(List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
                new Facts(List.of(), List.of(), List.of(), List.of())
        );
        WeeklySnapshotPayloadCodec codec = new WeeklySnapshotPayloadCodec();
        Versions versions = WeeklySnapshotPolicyV1.VERSIONS;
        jdbc.update("""
                INSERT INTO analytics_snapshots (
                    id, store_id, period_start, period_end, timezone, revision,
                    revision_reason_code, source_sync_job_id, source_sync_completed_at,
                    source_data_cutoff, facts_schema_version, metrics_contract_version,
                    calculation_version, quality_policy_version, quality_status,
                    facts_payload, facts_hash
                ) VALUES (?, ?, ?, ?, 'UTC', 1, 'INITIAL', ?, ?, ?, ?, ?, ?, ?, 'READY',
                    CAST(? AS jsonb), ?)
                """, id, store.id(), LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 23),
                sourceId, Timestamp.from(FINISHED), Timestamp.from(FINISHED),
                versions.factsSchemaVersion(), versions.metricContractVersion(),
                versions.calculationVersion(), versions.qualityPolicyVersion(),
                codec.serialize(payload), codec.hash(payload, List.of()));
        return id;
    }

    private TestStore store() {
        UUID connection = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO integration_connections (id, connection_key, source_system, display_name)
                VALUES (?, ?, 'LIVESKLAD', 'Synthetic coverage connection')
                """, connection, "historical-coverage-" + connection);
        jdbc.update("""
                INSERT INTO stores (id, connection_id, source_system, external_id, name, timezone)
                VALUES (?, ?, 'LIVESKLAD', ?, 'Synthetic coverage store', 'UTC')
                """, id, connection, id.toString());
        return new TestStore(id, connection);
    }

    private UUID successfulJob(TestStore store, String type, Instant from, Instant to,
            Instant finished) {
        UUID id = UUID.randomUUID();
        String phase = type.equals("HISTORICAL_SALES") ? "SALES" : "ORDERS";
        jdbc.update("""
                INSERT INTO sync_jobs (id, connection_id, job_type, status, phase,
                    period_start, period_end, cursor_start, current_window_end,
                    window_size_minutes, max_attempts, next_attempt_at, started_at, finished_at)
                VALUES (?, ?, ?, 'SUCCESS', ?, ?, ?, ?, ?, 180, 3, ?, ?, ?)
                """, id, store.connectionId(), type, phase, Timestamp.from(from),
                Timestamp.from(to), Timestamp.from(from), Timestamp.from(to),
                Timestamp.from(from), Timestamp.from(from), Timestamp.from(finished));
        return id;
    }

    private WeeklySnapshotJobRequest request(TestStore store, UUID jobId) {
        return new WeeklySnapshotJobRequest(
                store.id(), null, WeeklySnapshotJobType.INITIAL,
                new StoreKpiPeriod(LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 23)),
                "UTC", jobId, FINISHED, WeeklySnapshotPolicyV1.VERSIONS, null, 3
        );
    }

    private record TestStore(UUID id, UUID connectionId) {
    }
}
