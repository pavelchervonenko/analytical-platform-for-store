package com.storeanalytics.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = "app.maintenance.retention.scheduling-enabled=false")
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class DataRetentionRepositoryIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private DataRetentionRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void rollsDetailedInventoryIntoDailyAndMonthlyHistoryAtomically() {
        TestSource source = createSource("inventory-retention");
        UUID runId = addRun(
                source.connectionId(),
                source.storeId(),
                "SUCCESS",
                Instant.parse("2022-01-02T00:00:00Z")
        );
        addInventoryObservation(
                source,
                runId,
                "2022-01-01T08:00:00Z",
                "10.000",
                "100.00",
                "60.00"
        );
        addInventoryObservation(
                source,
                runId,
                "2022-01-01T10:00:00Z",
                "0.000",
                "110.00",
                "65.00"
        );
        addInventoryObservation(
                source,
                runId,
                "2022-01-01T18:00:00Z",
                "5.000",
                "120.00",
                "70.00"
        );

        RetentionBatchResult daily = repository.rollupDetailedInventory(
                Instant.parse("2023-01-01T00:00:00Z"),
                ZoneId.of("UTC"),
                10
        );

        assertThat(daily).isEqualTo(new RetentionBatchResult(1, 3));
        Map<String, Object> dailyRow = jdbcTemplate.queryForMap(
                """
                SELECT
                    opening_quantity,
                    closing_quantity,
                    minimum_quantity,
                    maximum_quantity,
                    closing_retail_price,
                    closing_cost_amount,
                    was_out_of_stock,
                    observation_count
                FROM store_product_inventory_daily
                WHERE store_id = ? AND product_id = ? AND snapshot_date = ?
                """,
                source.storeId(),
                source.productId(),
                Date.valueOf("2022-01-01")
        );
        assertThat(dailyRow.get("opening_quantity"))
                .isEqualTo(new BigDecimal("10.000"));
        assertThat(dailyRow.get("closing_quantity"))
                .isEqualTo(new BigDecimal("5.000"));
        assertThat(dailyRow.get("minimum_quantity"))
                .isEqualTo(new BigDecimal("0.000"));
        assertThat(dailyRow.get("maximum_quantity"))
                .isEqualTo(new BigDecimal("10.000"));
        assertThat(dailyRow.get("closing_retail_price"))
                .isEqualTo(new BigDecimal("120.00"));
        assertThat(dailyRow.get("closing_cost_amount"))
                .isEqualTo(new BigDecimal("70.00"));
        assertThat(dailyRow.get("was_out_of_stock")).isEqualTo(true);
        assertThat(((Number) dailyRow.get("observation_count")).longValue()).isEqualTo(3);

        RetentionBatchResult monthly = repository.rollupDailyInventory(
                LocalDate.of(2023, 1, 1),
                10
        );

        assertThat(monthly).isEqualTo(new RetentionBatchResult(1, 1));
        Map<String, Object> monthlyRow = jdbcTemplate.queryForMap(
                """
                SELECT
                    opening_quantity,
                    closing_quantity,
                    minimum_quantity,
                    maximum_quantity,
                    days_out_of_stock,
                    observed_days,
                    observation_count
                FROM store_product_inventory_monthly
                WHERE store_id = ? AND product_id = ? AND month_start = ?
                """,
                source.storeId(),
                source.productId(),
                Date.valueOf("2022-01-01")
        );
        assertThat(monthlyRow.get("opening_quantity"))
                .isEqualTo(new BigDecimal("10.000"));
        assertThat(monthlyRow.get("closing_quantity"))
                .isEqualTo(new BigDecimal("5.000"));
        assertThat(monthlyRow.get("minimum_quantity"))
                .isEqualTo(new BigDecimal("0.000"));
        assertThat(monthlyRow.get("maximum_quantity"))
                .isEqualTo(new BigDecimal("10.000"));
        assertThat(((Number) monthlyRow.get("days_out_of_stock")).intValue()).isOne();
        assertThat(((Number) monthlyRow.get("observed_days")).intValue()).isOne();
        assertThat(((Number) monthlyRow.get("observation_count")).longValue()).isEqualTo(3);

        assertThat(repository.rollupDetailedInventory(
                Instant.parse("2023-01-01T00:00:00Z"),
                ZoneId.of("UTC"),
                10
        )).isEqualTo(new RetentionBatchResult(0, 0));
        assertThat(repository.rollupDailyInventory(
                LocalDate.of(2023, 1, 1),
                10
        )).isEqualTo(new RetentionBatchResult(0, 0));
    }

    @Test
    void purgesOnlyEligibleTechnicalRowsAndPreservesOpenIssueRawData() {
        TestSource source = createSource("technical-retention");
        Instant oldFinished = Instant.parse("2020-01-02T00:00:00Z");
        UUID oldRunId = addRun(
                source.connectionId(),
                source.storeId(),
                "FAILED",
                oldFinished
        );
        UUID currentRunId = addRun(
                source.connectionId(),
                source.storeId(),
                "SUCCESS",
                Instant.parse("2026-07-01T00:00:00Z")
        );
        addRunError(oldRunId);

        UUID oldRawId = addRaw(
                source,
                oldRunId,
                "ordinary-sale",
                "a".repeat(64),
                "2020-01-01T00:00:00Z"
        );
        UUID currentRawId = addRaw(
                source,
                currentRunId,
                "ordinary-sale",
                "b".repeat(64),
                "2026-07-01T00:00:00Z"
        );
        UUID financialFactId = addSaleFact(source, oldRunId, oldRawId);
        jdbcTemplate.update(
                "UPDATE raw_record_versions SET payload_policy_version = 0 WHERE id = ?",
                oldRawId
        );
        assertThat(repository.countLegacyRawVersions()).isOne();
        UUID protectedRawId = addRaw(
                source,
                oldRunId,
                "protected-sale",
                "c".repeat(64),
                "2020-01-01T00:00:00Z"
        );
        addRaw(
                source,
                currentRunId,
                "protected-sale",
                "d".repeat(64),
                "2026-07-01T00:00:00Z"
        );
        addOpenIssue(source.storeId(), source.connectionId() + ":protected-sale");

        UUID jobId = addCompletedJob(source.connectionId(), "SUCCESS", oldFinished);
        UUID currentJobId = addCompletedJob(
                source.connectionId(),
                "SUCCESS",
                Instant.parse("2026-07-01T00:00:00Z")
        );
        addClosedIssue(source.storeId(), "closed-sale", oldFinished);

        long rawDeleted = repository.purgeRawVersions(
                Instant.parse("2025-01-01T00:00:00Z"),
                Instant.parse("2025-01-01T00:00:00Z"),
                100
        );
        SyncRunPurgeResult runsDeleted = repository.purgeSyncRuns(
                Instant.parse("2025-01-01T00:00:00Z"),
                Instant.parse("2025-01-01T00:00:00Z"),
                100
        );
        long jobsDeleted = repository.purgeSyncJobs(
                Instant.parse("2025-01-01T00:00:00Z"),
                Instant.parse("2025-01-01T00:00:00Z"),
                100
        );
        long issuesDeleted = repository.purgeClosedQualityIssues(
                Instant.parse("2025-01-01T00:00:00Z"),
                100
        );

        assertThat(rawDeleted).isOne();
        assertThat(runsDeleted).isEqualTo(new SyncRunPurgeResult(1, 1));
        assertThat(jobsDeleted).isOne();
        assertThat(issuesDeleted).isOne();
        assertThat(countById("raw_record_versions", oldRawId)).isZero();
        assertThat(repository.countLegacyRawVersions()).isZero();
        assertThat(countById("raw_record_versions", currentRawId)).isOne();
        assertThat(countById("raw_record_versions", protectedRawId)).isOne();
        assertThat(countById("sales_documents", financialFactId)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT raw_record_version_id FROM sales_documents WHERE id = ?",
                UUID.class,
                financialFactId
        )).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT net_amount FROM sales_documents WHERE id = ?",
                BigDecimal.class,
                financialFactId
        )).isEqualByComparingTo("100.00");
        assertThat(countById("sync_runs", oldRunId)).isZero();
        assertThat(countById("sync_jobs", jobId)).isZero();
        assertThat(countById("sync_jobs", currentJobId)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM data_quality_issues WHERE status = 'OPEN'",
                Long.class
        )).isEqualTo(1);
    }

    @Test
    void preservesLatestTerminalAndDataCoverageWhenTheyAreOlderThanRetention() {
        TestSource source = createSource("latest-sync-state");
        Instant oldFinished = Instant.parse("2020-01-02T00:00:00Z");
        UUID coverageRunId = addRun(
                source.connectionId(),
                source.storeId(),
                "SUCCESS",
                oldFinished.minusSeconds(86_400)
        );
        UUID runId = addRun(
                source.connectionId(),
                source.storeId(),
                "FAILED",
                oldFinished
        );
        UUID jobId = addCompletedJob(source.connectionId(), "FAILED", oldFinished);

        assertThat(repository.purgeSyncRuns(
                Instant.parse("2025-01-01T00:00:00Z"),
                Instant.parse("2025-01-01T00:00:00Z"),
                100
        )).isEqualTo(new SyncRunPurgeResult(0, 0));
        assertThat(repository.purgeSyncJobs(
                Instant.parse("2025-01-01T00:00:00Z"),
                Instant.parse("2025-01-01T00:00:00Z"),
                100
        )).isZero();
        assertThat(countById("sync_runs", coverageRunId)).isOne();
        assertThat(countById("sync_runs", runId)).isOne();
        assertThat(countById("sync_jobs", jobId)).isOne();
    }

    @Test
    void purgesExpiredAuditByClassUnlessAnActiveHoldExists() {
        Instant now = Instant.parse("2026-07-24T10:00:00Z");
        UUID financialId = addAudit(
                "PAYROLL_PAID",
                "FINANCIAL",
                null
        );
        UUID businessId = addAudit(
                "EMPLOYEE_RATING_FINALIZED",
                "BUSINESS",
                Instant.parse("2025-01-01T00:00:00Z")
        );
        UUID heldId = addAudit(
                "EMPLOYEE_RATING_PARTICIPATION_CHANGED",
                "BUSINESS",
                Instant.parse("2025-01-01T00:00:00Z")
        );
        jdbcTemplate.update(
                """
                INSERT INTO audit_retention_holds (audit_log_id, reason)
                VALUES (?, 'Active investigation')
                """,
                heldId
        );

        assertThat(repository.countExpiredAuditEntries(now)).isOne();
        assertThat(repository.purgeExpiredAuditEntries(now, 100)).isOne();
        assertThat(countById("audit_log", financialId)).isOne();
        assertThat(countById("audit_log", businessId)).isZero();
        assertThat(countById("audit_log", heldId)).isOne();

        jdbcTemplate.update(
                """
                UPDATE audit_retention_holds
                SET released_at = clock_timestamp()
                WHERE audit_log_id = ?
                """,
                heldId
        );

        assertThat(repository.purgeExpiredAuditEntries(now, 100)).isOne();
        assertThat(countById("audit_log", heldId)).isZero();
        assertThat(countById("audit_log", financialId)).isOne();
    }

    private TestSource createSource(String suffix) {
        UUID connectionId = jdbcTemplate.queryForObject(
                """
                SELECT id
                FROM integration_connections
                WHERE connection_key = 'livesklad-default'
                """,
                UUID.class
        );
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO stores (
                    id, connection_id, source_system, external_id, name
                ) VALUES (?, ?, 'LIVESKLAD', ?, ?)
                """,
                storeId,
                connectionId,
                "store-" + suffix,
                "Store " + suffix
        );
        jdbcTemplate.update(
                """
                INSERT INTO products (
                    id, connection_id, source_system, external_id, name
                ) VALUES (?, ?, 'LIVESKLAD', ?, ?)
                """,
                productId,
                connectionId,
                "product-" + suffix,
                "Product " + suffix
        );
        return new TestSource(connectionId, storeId, productId);
    }

    private UUID addRun(
            UUID connectionId,
            UUID storeId,
            String status,
            Instant finishedAt
    ) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO sync_runs (
                    id,
                    connection_id,
                    store_id,
                    source_system,
                    trigger_type,
                    sync_scope,
                    status,
                    period_start,
                    period_end,
                    started_at,
                    finished_at
                ) VALUES (?, ?, ?, 'LIVESKLAD', 'SCHEDULED', 'SALES', ?, ?, ?, ?, ?)
                """,
                id,
                connectionId,
                storeId,
                status,
                Timestamp.from(finishedAt.minusSeconds(172_800)),
                Timestamp.from(finishedAt.minusSeconds(86_400)),
                Timestamp.from(finishedAt.minusSeconds(60)),
                Timestamp.from(finishedAt)
        );
        return id;
    }

    private void addInventoryObservation(
            TestSource source,
            UUID runId,
            String observedAt,
            String quantity,
            String retailPrice,
            String costAmount
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO store_product_inventory_history (
                    store_id,
                    product_id,
                    quantity,
                    retail_price,
                    cost_amount,
                    observed_at,
                    sync_run_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                source.storeId(),
                source.productId(),
                new BigDecimal(quantity),
                new BigDecimal(retailPrice),
                new BigDecimal(costAmount),
                Timestamp.from(Instant.parse(observedAt)),
                runId
        );
    }

    private UUID addRaw(
            TestSource source,
            UUID runId,
            String externalId,
            String hash,
            String seenAt
    ) {
        UUID id = UUID.randomUUID();
        Instant seen = Instant.parse(seenAt);
        jdbcTemplate.update(
                """
                INSERT INTO raw_record_versions (
                    id,
                    connection_id,
                    store_id,
                    source_system,
                    entity_type,
                    external_id,
                    payload,
                    payload_hash,
                    payload_policy_version,
                    first_seen_at,
                    last_seen_at,
                    first_sync_run_id,
                    last_sync_run_id,
                    normalization_status,
                    normalized_at
                ) VALUES (
                    ?, ?, ?, 'LIVESKLAD', 'SALE_DOCUMENT', ?, '{}'::jsonb, ?, 1,
                    ?, ?, ?, ?, 'NORMALIZED', ?
                )
                """,
                id,
                source.connectionId(),
                source.storeId(),
                externalId,
                hash,
                Timestamp.from(seen),
                Timestamp.from(seen),
                runId,
                runId,
                Timestamp.from(seen)
        );
        return id;
    }

    private UUID addSaleFact(
            TestSource source,
            UUID runId,
            UUID rawRecordVersionId
    ) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO sales_documents (
                    id,
                    connection_id,
                    source_system,
                    external_id,
                    store_id,
                    document_kind,
                    source_document_type,
                    occurred_at,
                    business_date,
                    net_amount,
                    cost_amount,
                    raw_record_version_id,
                    last_sync_run_id
                ) VALUES (
                    ?, ?, 'LIVESKLAD', 'ordinary-sale', ?, 'SALE', 'SALE',
                    '2020-01-01T10:00:00Z', '2020-01-01', 100.00, 60.00, ?, ?
                )
                """,
                id,
                source.connectionId(),
                source.storeId(),
                rawRecordVersionId,
                runId
        );
        return id;
    }

    private void addRunError(UUID runId) {
        jdbcTemplate.update(
                """
                INSERT INTO sync_run_errors (
                    sync_run_id,
                    stage,
                    error_code,
                    error_message
                ) VALUES (?, 'TEST', 'TEST_FAILURE', 'Expected failure')
                """,
                runId
        );
    }

    private void addOpenIssue(UUID storeId, String entityId) {
        jdbcTemplate.update(
                """
                INSERT INTO data_quality_issues (
                    store_id,
                    entity_type,
                    entity_id,
                    issue_code,
                    severity,
                    status,
                    message
                ) VALUES (
                    ?, 'SALE_DOCUMENT', ?, 'SALE_PAYMENT_MISMATCH',
                    'WARNING', 'OPEN', 'Expected open issue'
                )
                """,
                storeId,
                entityId
        );
    }

    private void addClosedIssue(UUID storeId, String entityId, Instant resolvedAt) {
        jdbcTemplate.update(
                """
                INSERT INTO data_quality_issues (
                    store_id,
                    entity_type,
                    entity_id,
                    issue_code,
                    severity,
                    status,
                    message,
                    detected_at,
                    resolved_at
                ) VALUES (
                    ?, 'SALE', ?, 'SALE_ITEM_NET_MISMATCH',
                    'WARNING', 'RESOLVED', 'Expected closed issue', ?, ?
                )
                """,
                storeId,
                entityId,
                Timestamp.from(resolvedAt.minusSeconds(60)),
                Timestamp.from(resolvedAt)
        );
    }

    private UUID addCompletedJob(
            UUID connectionId,
            String status,
            Instant finishedAt
    ) {
        UUID id = UUID.randomUUID();
        Instant periodStart = finishedAt.minusSeconds(3_600);
        Instant cursorStart = "SUCCESS".equals(status)
                ? finishedAt : periodStart;
        jdbcTemplate.update(
                """
                INSERT INTO sync_jobs (
                    id,
                    connection_id,
                    job_type,
                    status,
                    phase,
                    period_start,
                    period_end,
                    cursor_start,
                    current_window_end,
                    window_size_minutes,
                    max_attempts,
                    next_attempt_at,
                    started_at,
                    finished_at
                ) VALUES (
                    ?, ?, 'INCREMENTAL', ?, 'RETURNS', ?, ?, ?, ?,
                    60, 5, ?, ?, ?
                )
                """,
                id,
                connectionId,
                status,
                Timestamp.from(periodStart),
                Timestamp.from(finishedAt),
                Timestamp.from(cursorStart),
                Timestamp.from(finishedAt),
                Timestamp.from(finishedAt),
                Timestamp.from(periodStart),
                Timestamp.from(finishedAt)
        );
        return id;
    }

    private UUID addAudit(
            String action,
            String retentionClass,
            Instant retainUntil
    ) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO audit_log (
                    id,
                    action,
                    entity_type,
                    entity_id,
                    retention_class,
                    retain_until,
                    created_at
                ) VALUES (?, ?, 'TEST', ?, ?, ?, ?)
                """,
                id,
                action,
                id.toString(),
                retentionClass,
                retainUntil == null ? null : Timestamp.from(retainUntil),
                Timestamp.from(Instant.parse("2020-01-01T00:00:00Z"))
        );
        return id;
    }

    private long countById(String table, UUID id) {
        if (!table.matches("[a-z_]+")) {
            throw new IllegalArgumentException("unsafe table name");
        }
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE id = ?",
                Long.class,
                id
        );
        return count == null ? 0 : count;
    }

    private record TestSource(
            UUID connectionId,
            UUID storeId,
            UUID productId
    ) {
    }
}
