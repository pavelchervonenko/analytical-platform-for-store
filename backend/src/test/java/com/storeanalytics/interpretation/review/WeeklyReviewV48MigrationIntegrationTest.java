package com.storeanalytics.interpretation.review;

import static com.storeanalytics.interpretation.review.WeeklyReviewTestPayload.snapshotPayload;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class WeeklyReviewV48MigrationIntegrationTest {

    private static final UUID STORE_ID = UUID.fromString(
            "48000000-0000-0000-0000-000000000001"
    );
    private static final UUID SNAPSHOT_ID = UUID.fromString(
            "48000000-0000-0000-0000-000000000002"
    );
    private static final UUID JOB_ID = UUID.fromString(
            "48000000-0000-0000-0000-000000000003"
    );
    private static final UUID FINAL_ATTEMPT_ID = UUID.fromString(
            "48000000-0000-0000-0000-000000000004"
    );
    private static final UUID STARTED_ATTEMPT_ID = UUID.fromString(
            "48000000-0000-0000-0000-000000000005"
    );

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void migratesPopulatedV47AttemptsAndRestoresImmutability()
            throws SQLException {
        flyway("47").migrate();
        insertV47Fixture();

        flyway(null).migrate();

        assertThat(currentVersion()).isEqualTo("48");
        assertThat(providerOutcome(FINAL_ATTEMPT_ID))
                .isEqualTo("RESPONSE_RECEIVED");
        assertThat(providerOutcome(STARTED_ATTEMPT_ID)).isNull();
        assertThatThrownBy(() -> updateFinalAttempt())
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(
                        "Final weekly review AI attempts are immutable"
                );
    }

    private void insertV47Fixture() throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO stores (
                        id, connection_id, source_system, external_id, name,
                        timezone
                    )
                    SELECT
                        '48000000-0000-0000-0000-000000000001',
                        id,
                        'LIVESKLAD',
                        'weekly-review-v48-upgrade',
                        'Weekly review V48 upgrade',
                        'Europe/Moscow'
                    FROM integration_connections
                    WHERE connection_key = 'livesklad-default'
                    """);
        }
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO weekly_review_snapshots (
                         id, store_id, period_start, period_end, timezone,
                         revision, report_contract_version,
                         metrics_policy_version, snapshot_policy_version,
                         quality_policy_version, report_state, report_payload,
                         content_hash
                     ) VALUES (
                         ?, ?, '2026-08-17', '2026-08-23', 'Europe/Moscow',
                         1, 2, 'metrics-v4', 'snapshot-v7', 'quality-v4',
                         'READY', CAST(? AS jsonb), ?
                     )
                     """)) {
            statement.setObject(1, SNAPSHOT_ID);
            statement.setObject(2, STORE_ID);
            statement.setString(
                    3,
                    snapshotPayload(
                            SNAPSHOT_ID,
                            LocalDate.of(2026, 8, 17),
                            1,
                            "READY"
                    )
            );
            statement.setString(4, "a".repeat(64));
            statement.executeUpdate();
        }
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO weekly_review_ai_jobs (
                        id, snapshot_id, prompt_version,
                        content_schema_version, provider_code,
                        requested_model, status, attempt_count,
                        max_attempts, next_attempt_at, deadline_at,
                        lease_owner, lease_until
                    ) VALUES (
                        '48000000-0000-0000-0000-000000000003',
                        '48000000-0000-0000-0000-000000000002',
                        'weekly-interpretation-v22',
                        4,
                        'YANDEX',
                        'gpt://folder/yandexgpt-5.1',
                        'RUNNING',
                        2,
                        2,
                        now(),
                        now() + interval '2 hours',
                        'migration-test-worker',
                        now() + interval '4 minutes'
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO weekly_review_ai_attempts (
                        id, job_id, attempt_number, status, request_hash,
                        input_hash, input_payload, response_payload,
                        estimated_cost, started_at, finished_at
                    ) VALUES (
                        '48000000-0000-0000-0000-000000000004',
                        '48000000-0000-0000-0000-000000000003',
                        1,
                        'SUCCEEDED',
                        repeat('b', 64),
                        repeat('c', 64),
                        '{}'::jsonb,
                        '{}',
                        3.00,
                        now() - interval '1 minute',
                        now()
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO weekly_review_ai_attempts (
                        id, job_id, attempt_number, status, request_hash,
                        input_hash, input_payload, estimated_cost, started_at
                    ) VALUES (
                        '48000000-0000-0000-0000-000000000005',
                        '48000000-0000-0000-0000-000000000003',
                        2,
                        'STARTED',
                        repeat('d', 64),
                        repeat('e', 64),
                        '{}'::jsonb,
                        3.00,
                        now()
                    )
                    """);
        }
    }

    private String currentVersion() throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT version
                     FROM flyway_schema_history
                     WHERE success = true
                     ORDER BY installed_rank DESC
                     LIMIT 1
                     """)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private String providerOutcome(UUID attemptId) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT provider_outcome
                     FROM weekly_review_ai_attempts
                     WHERE id = ?
                     """)) {
            statement.setObject(1, attemptId);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    private void updateFinalAttempt() throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE weekly_review_ai_attempts
                     SET error_message = 'must fail'
                     WHERE id = ?
                     """)) {
            statement.setObject(1, FINAL_ATTEMPT_ID);
            statement.executeUpdate();
        }
    }

    private Flyway flyway(String target) {
        var configuration = Flyway.configure()
                .dataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword()
                )
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );
    }
}
