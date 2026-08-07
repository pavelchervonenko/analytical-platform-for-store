package com.storeanalytics.notification.fanout;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.interpretation.contract.CanonicalLlmJson;
import com.storeanalytics.interpretation.contract.LlmCanonicalJsonCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
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
class NotificationEventFanoutIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private NotificationEventFanoutService service;

    @Autowired
    private LlmCanonicalJsonCodec jsonCodec;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void atomicallyCreatesOneDeliveryAndTerminalReceipt() throws IOException {
        Fixture fixture = fixture();

        NotificationFanoutResult result = service.processNext().orElseThrow();

        assertThat(result.eventId()).isEqualTo(fixture.eventId());
        assertThat(result.outcome()).isEqualTo(
                NotificationFanoutOutcome.DELIVERIES_CREATED
        );
        assertThat(result.recipientCount()).isOne();
        assertThat(result.deliveryCount()).isOne();
        assertThat(service.processNext()).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_event_fanout_receipts "
                        + "WHERE event_id = ?",
                Integer.class,
                fixture.eventId()
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_deliveries WHERE event_id = ?",
                Integer.class,
                fixture.eventId()
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT rendered_text FROM notification_deliveries WHERE event_id = ?",
                String.class,
                fixture.eventId()
        )).contains("НЕДЕЛЯ · ОТЧЁТ ГОТОВ", "Магазин fanout", "Анна");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM notification_deliveries WHERE event_id = ?",
                String.class,
                fixture.eventId()
        )).isEqualTo("PENDING");
    }

    @Test
    void explicitDisabledPreferenceProducesTerminalNoRecipientOutcome()
            throws IOException {
        Fixture fixture = fixture();
        jdbcTemplate.update(
                """
                INSERT INTO notification_preferences (
                    user_id, store_id, channel, event_type, enabled
                ) VALUES (?, ?, 'TELEGRAM', 'WEEKLY_REPORT_READY', false)
                """,
                fixture.userId(),
                fixture.storeId()
        );

        NotificationFanoutResult result = service.processNext().orElseThrow();

        assertThat(result.eventId()).isEqualTo(fixture.eventId());
        assertThat(result.outcome()).isEqualTo(
                NotificationFanoutOutcome.NO_RECIPIENTS
        );
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_deliveries WHERE event_id = ?",
                Integer.class,
                fixture.eventId()
        )).isZero();
    }

    private Fixture fixture() throws IOException {
        Instant now = Instant.now();
        UUID connectionId = jdbcTemplate.queryForObject(
                "SELECT id FROM integration_connections "
                        + "WHERE connection_key = 'livesklad-default'",
                UUID.class
        );
        UUID storeId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO stores (id, connection_id, source_system, external_id, "
                        + "name) VALUES (?, ?, 'LIVESKLAD', ?, ?)",
                storeId,
                connectionId,
                "fanout-" + storeId,
                "Магазин fanout"
        );
        UUID syncJobId = insertSyncJob(connectionId, now);
        UUID snapshotId = insertSnapshot(storeId, syncJobId, now);
        UUID jobId = insertLlmJob(snapshotId, now);
        String response = new String(
                getClass().getResourceAsStream(
                        "/contracts/llm/examples/"
                                + "weekly-interpretation-content-v1-ready.json"
                ).readAllBytes(),
                StandardCharsets.UTF_8
        );
        CanonicalLlmJson content = jsonCodec.canonicalize(response);
        UUID attemptId = insertAttempt(jobId, content, now);
        UUID interpretationId = insertInterpretation(
                storeId,
                snapshotId,
                jobId,
                attemptId,
                content,
                now
        );
        insertSnapshotEmployee(snapshotId, storeId);
        UUID eventId = insertEvent(
                storeId,
                snapshotId,
                interpretationId,
                now
        );
        UUID userId = insertRecipient(storeId, now);
        return new Fixture(eventId, storeId, userId);
    }

    private UUID insertSyncJob(UUID connectionId, Instant now) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO sync_jobs (
                    id, connection_id, job_type, status, phase,
                    period_start, period_end, cursor_start, current_window_end,
                    window_size_minutes, max_attempts, next_attempt_at,
                    started_at, finished_at
                ) VALUES (
                    ?, ?, 'INCREMENTAL', 'SUCCESS', 'RETURNS',
                    ?, ?, ?, ?, 60, 5, ?, ?, ?
                )
                """,
                id,
                connectionId,
                Timestamp.from(now.minusSeconds(604_800)),
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now.minusSeconds(60)),
                Timestamp.from(now)
        );
        return id;
    }

    private UUID insertSnapshot(UUID storeId, UUID syncJobId, Instant now) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO analytics_snapshots (
                    id, store_id, period_start, period_end, timezone, revision,
                    revision_reason_code, source_sync_job_id,
                    source_sync_completed_at, source_data_cutoff,
                    facts_schema_version, metrics_contract_version,
                    calculation_version, quality_policy_version, quality_status,
                    facts_payload, facts_hash
                ) VALUES (
                    ?, ?, ?, ?, 'Europe/Kaliningrad', 1, 'INITIAL', ?, ?, ?,
                    1, 'metrics-v1', 'calculation-v1', 'quality-v1', 'READY',
                    '{}'::jsonb, ?
                )
                """,
                id,
                storeId,
                LocalDate.parse("2026-07-27"),
                LocalDate.parse("2026-08-02"),
                syncJobId,
                Timestamp.from(now),
                Timestamp.from(now),
                "0".repeat(64)
        );
        return id;
    }

    private UUID insertLlmJob(UUID snapshotId, Instant now) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO llm_analysis_jobs (
                    id, snapshot_id, generation_revision, trigger_type,
                    provider_code, requested_model, provider_config_version,
                    content_schema_version, prompt_version,
                    analysis_policy_version, budget_policy_version,
                    input_hash, status, phase, attempt_count,
                    max_transport_retries, max_validation_retries,
                    next_attempt_at, deadline_at, started_at, finished_at
                ) VALUES (
                    ?, ?, 1, 'INITIAL', 'TEST', 'test-model', 'test-config',
                    1, 'weekly-interpretation-v1', 'analysis-v1', 'budget-v1',
                    ?, 'SUCCESS', 'PUBLISH', 1, 1, 1, ?, ?, ?, ?
                )
                """,
                id,
                snapshotId,
                "1".repeat(64),
                Timestamp.from(now),
                Timestamp.from(now.plusSeconds(3600)),
                Timestamp.from(now.minusSeconds(30)),
                Timestamp.from(now)
        );
        return id;
    }

    private UUID insertAttempt(
            UUID jobId,
            CanonicalLlmJson content,
            Instant now
    ) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO llm_analysis_attempts (
                    id, job_id, attempt_number, attempt_type, status,
                    provider_code, requested_model, request_hash,
                    response_hash, response_body, started_at,
                    response_received_at, finished_at
                ) VALUES (
                    ?, ?, 1, 'INITIAL', 'SUCCEEDED', 'TEST', 'test-model',
                    ?, ?, ?, ?, ?, ?
                )
                """,
                id,
                jobId,
                "2".repeat(64),
                content.contentHash(),
                content.canonicalJson(),
                Timestamp.from(now.minusSeconds(20)),
                Timestamp.from(now.minusSeconds(10)),
                Timestamp.from(now)
        );
        return id;
    }

    private UUID insertInterpretation(
            UUID storeId,
            UUID snapshotId,
            UUID jobId,
            UUID attemptId,
            CanonicalLlmJson content,
            Instant now
    ) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO llm_interpretations (
                    id, store_id, snapshot_id, analysis_job_id,
                    successful_attempt_id, period_start, period_end, revision,
                    publication_reason_code, content_payload, content_hash,
                    validated_at, published_at
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, 1, 'INITIAL', CAST(? AS jsonb), ?, ?, ?
                )
                """,
                id,
                storeId,
                snapshotId,
                jobId,
                attemptId,
                LocalDate.parse("2026-07-27"),
                LocalDate.parse("2026-08-02"),
                content.canonicalJson(),
                content.contentHash(),
                Timestamp.from(now),
                Timestamp.from(now)
        );
        return id;
    }

    private void insertSnapshotEmployee(UUID snapshotId, UUID storeId) {
        UUID employeeId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO employees (id, source_system, external_id, "
                        + "full_name) VALUES (?, 'MANUAL', ?, 'Анна')",
                employeeId,
                "employee-" + employeeId
        );
        jdbcTemplate.update(
                "INSERT INTO employee_store_assignments (employee_id, store_id) "
                        + "VALUES (?, ?)",
                employeeId,
                storeId
        );
        jdbcTemplate.update(
                "INSERT INTO analytics_snapshot_employees (snapshot_id, "
                        + "employee_id, employee_ref, display_name_snapshot) "
                        + "VALUES (?, ?, 'E01', 'Анна')",
                snapshotId,
                employeeId
        );
    }

    private UUID insertEvent(
            UUID storeId,
            UUID snapshotId,
            UUID interpretationId,
            Instant now
    ) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO notification_events (
                    id, store_id, event_type, audience, interpretation_id,
                    snapshot_id, deduplication_key, notification_policy_version,
                    priority, event_payload, payload_hash, not_before, expires_at
                ) VALUES (
                    ?, ?, 'WEEKLY_REPORT_READY', 'MANAGER', ?, ?, ?,
                    'weekly-notification-v1', 'NORMAL', '{}'::jsonb, ?, ?, ?
                )
                """,
                id,
                storeId,
                interpretationId,
                snapshotId,
                "fanout-test:" + id,
                "3".repeat(64),
                Timestamp.from(now.minusSeconds(1)),
                Timestamp.from(now.plusSeconds(86_400))
        );
        return id;
    }

    private UUID insertRecipient(UUID storeId, Instant now) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO app_users (id, email, password_hash, display_name, "
                        + "role) VALUES (?, ?, '{noop}test', 'Manager', 'MANAGER')",
                userId,
                "fanout-" + userId + "@example.test"
        );
        jdbcTemplate.update(
                "INSERT INTO user_store_access (user_id, store_id) VALUES (?, ?)",
                userId,
                storeId
        );
        jdbcTemplate.update(
                """
                INSERT INTO telegram_subscriptions (
                    user_id, bot_code, telegram_user_id, telegram_chat_id,
                    status, quiet_hours_enabled, confirmed_at
                ) VALUES (?, 'store-analytics-primary', ?, ?, 'ACTIVE', false, ?)
                """,
                userId,
                Math.abs(userId.getMostSignificantBits()),
                Math.abs(userId.getLeastSignificantBits()),
                Timestamp.from(now)
        );
        return userId;
    }

    private record Fixture(UUID eventId, UUID storeId, UUID userId) {
    }
}
