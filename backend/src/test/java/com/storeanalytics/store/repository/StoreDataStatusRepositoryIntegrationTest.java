package com.storeanalytics.store.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class StoreDataStatusRepositoryIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private StoreDataStatusRepository repository;

    @Autowired
    private DataFreshnessRepository freshnessRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM data_quality_issues");
        jdbcTemplate.update("DELETE FROM sync_run_errors");
        jdbcTemplate.update("DELETE FROM sync_runs");
        jdbcTemplate.update("DELETE FROM sync_jobs");
        jdbcTemplate.update("DELETE FROM stores");
    }

    @Test
    void readsCoverageActivityFailureAndStoreScopedQualityIssues() {
        UUID connectionId = jdbcTemplate.queryForObject(
                "SELECT id FROM integration_connections WHERE connection_key = 'livesklad-default'",
                UUID.class
        );
        UUID storeId = addStore(connectionId, "target-store");
        UUID otherStoreId = addStore(connectionId, "other-store");

        Instant salesEnd = Instant.parse("2026-07-21T22:00:00Z");
        Instant salesFinished = Instant.parse("2026-07-22T05:00:00Z");
        addRun(connectionId, null, "SALES", "SUCCESS", salesEnd, salesFinished, null);
        addRun(
                connectionId,
                otherStoreId,
                "SALES",
                "SUCCESS",
                Instant.parse("2026-07-30T22:00:00Z"),
                Instant.parse("2026-07-31T05:00:00Z"),
                null
        );
        Instant returnsEnd = Instant.parse("2026-07-20T22:00:00Z");
        Instant returnsFinished = Instant.parse("2026-07-22T05:30:00Z");
        addRun(
                connectionId,
                storeId,
                "RETURNS",
                "PARTIAL_SUCCESS",
                returnsEnd,
                returnsFinished,
                "Some records were skipped because dependencies are missing"
        );
        Instant failedAt = Instant.parse("2026-07-22T06:00:00Z");
        addFailedRun(connectionId, storeId, failedAt, "Sales synchronization failed: TimeoutException");
        UUID jobId = addWaitingJob(connectionId);

        addQualityIssue(storeId, "target-open", "OPEN");
        addHiddenZeroCostIssue(storeId);
        addQualityIssue(storeId, "target-resolved", "RESOLVED");
        addQualityIssue(otherStoreId, "other-open", "OPEN");

        StoreDataStatusSnapshot result = repository.findByStoreId(storeId).orElseThrow();

        assertThat(result.storeId()).isEqualTo(storeId);
        assertThat(result.timezone()).isEqualTo("Europe/Kaliningrad");
        assertThat(result.salesThroughExclusive()).isEqualTo(salesEnd);
        assertThat(result.salesCompletedAt()).isEqualTo(salesFinished);
        assertThat(result.returnsThroughExclusive()).isEqualTo(returnsEnd);
        assertThat(result.returnsCompletedAt()).isEqualTo(returnsFinished);
        assertThat(result.activeSyncId()).isEqualTo(jobId);
        assertThat(result.activeSyncType()).isEqualTo("JOB");
        assertThat(result.activeSyncStatus()).isEqualTo("WAITING_RETRY");
        assertThat(result.activeSyncPhase()).isEqualTo("RETURNS");
        assertThat(result.latestTerminalStatus()).isEqualTo("FAILED");
        assertThat(result.lastError()).isEqualTo("Sales synchronization failed: TimeoutException");
        assertThat(result.lastErrorAt()).isEqualTo(failedAt);
        assertThat(result.openQualityIssueCount()).isEqualTo(1);

        DataFreshnessSnapshot freshness = freshnessRepository.load();
        assertThat(freshness.oldestSalesThrough()).isEqualTo(salesEnd);
        assertThat(freshness.oldestReturnsThrough()).isEqualTo(returnsEnd);
        assertThat(freshness.storesWithoutSales()).isZero();
        assertThat(freshness.storesWithoutReturns()).isEqualTo(1);
    }

    @Test
    void returnsEmptyForUnknownStore() {
        assertThat(repository.findByStoreId(UUID.randomUUID())).isEmpty();
    }

    private UUID addStore(UUID connectionId, String externalId) {
        UUID storeId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO stores (id, connection_id, source_system, external_id, name)
                VALUES (?, ?, 'LIVESKLAD', ?, ?)
                """,
                storeId,
                connectionId,
                externalId,
                externalId
        );
        return storeId;
    }

    private void addRun(
            UUID connectionId,
            UUID storeId,
            String scope,
            String status,
            Instant periodEnd,
            Instant finishedAt,
            String errorSummary
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO sync_runs (
                    id, connection_id, store_id, source_system, trigger_type, sync_scope,
                    status, period_start, period_end, started_at, finished_at, error_summary
                ) VALUES (?, ?, ?, 'LIVESKLAD', 'SCHEDULED', ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                connectionId,
                storeId,
                scope,
                status,
                Timestamp.from(periodEnd.minusSeconds(86_400)),
                Timestamp.from(periodEnd),
                Timestamp.from(finishedAt.minusSeconds(60)),
                Timestamp.from(finishedAt),
                errorSummary
        );
    }

    private void addFailedRun(
            UUID connectionId,
            UUID storeId,
            Instant failedAt,
            String errorSummary
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO sync_runs (
                    id, connection_id, store_id, source_system, trigger_type, sync_scope,
                    status, started_at, finished_at, error_summary
                ) VALUES (?, ?, ?, 'LIVESKLAD', 'SCHEDULED', 'SALES', 'FAILED', ?, ?, ?)
                """,
                UUID.randomUUID(),
                connectionId,
                storeId,
                Timestamp.from(failedAt.minusSeconds(30)),
                Timestamp.from(failedAt),
                errorSummary
        );
    }

    private UUID addWaitingJob(UUID connectionId) {
        UUID jobId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO sync_jobs (
                    id, connection_id, job_type, status, phase, period_start, period_end,
                    cursor_start, current_window_end, window_size_minutes, max_attempts,
                    next_attempt_at, started_at
                ) VALUES (
                    ?, ?, 'INCREMENTAL', 'WAITING_RETRY', 'RETURNS',
                    TIMESTAMPTZ '2026-07-20 00:00:00+00',
                    TIMESTAMPTZ '2026-07-23 00:00:00+00',
                    TIMESTAMPTZ '2026-07-21 00:00:00+00',
                    TIMESTAMPTZ '2026-07-22 00:00:00+00',
                    1440, 3,
                    TIMESTAMPTZ '2026-07-22 08:05:00+00',
                    TIMESTAMPTZ '2026-07-22 04:00:00+00'
                )
                """,
                jobId,
                connectionId
        );
        return jobId;
    }

    private void addHiddenZeroCostIssue(UUID storeId) {
        jdbcTemplate.update(
                """
                INSERT INTO data_quality_issues (
                    id, store_id, entity_type, entity_id, issue_code, severity,
                    status, message
                ) VALUES (?, ?, 'SALE_ITEM', 'zero-cost-item',
                          'ZERO_UNEXPECTED_COST', 'WARNING', 'OPEN', 'Internal issue')
                """,
                UUID.randomUUID(),
                storeId
        );
    }

    private void addQualityIssue(UUID storeId, String entityId, String status) {
        boolean open = "OPEN".equals(status);
        jdbcTemplate.update(
                """
                INSERT INTO data_quality_issues (
                    id, store_id, entity_type, entity_id, issue_code, severity,
                    status, message, resolved_at
                ) VALUES (?, ?, 'STORE', ?, 'TEST_ISSUE', 'WARNING', ?, 'Test issue', ?)
                """,
                UUID.randomUUID(),
                storeId,
                entityId,
                status,
                open ? null : Timestamp.from(Instant.parse("2026-07-22T07:00:00Z"))
        );
    }
}
