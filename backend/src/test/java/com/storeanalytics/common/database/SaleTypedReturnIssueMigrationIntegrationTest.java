package com.storeanalytics.common.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class SaleTypedReturnIssueMigrationIntegrationTest {

    private static final String STORE_ID =
            "00000000-0000-0000-0000-000000000531";
    private static final String OTHER_STORE_ID =
            "00000000-0000-0000-0000-000000000532";
    private static final String SYNC_RUN_ID =
            "00000000-0000-0000-0000-000000000533";

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void resolvesOnlyExactActiveSameStoreSaleWithLatestSkippedSalePayload()
            throws Exception {
        flyway("52").migrate();
        insertFixtures();

        flyway("53").migrate();

        assertThat(issueStatus("exact-sale")).isEqualTo("RESOLVED");
        assertThat(issueStatus("latest-return")).isEqualTo("OPEN");
        assertThat(issueStatus("deleted-sale")).isEqualTo("OPEN");
        assertThat(issueStatus("foreign-store-scope")).isEqualTo("OPEN");
        assertThat(currentVersion()).isEqualTo("53");

        String resolvedAt = issueResolvedAt("exact-sale");
        rerunMigrationSql();
        assertThat(issueStatus("exact-sale")).isEqualTo("RESOLVED");
        assertThat(issueResolvedAt("exact-sale")).isEqualTo(resolvedAt);
    }

    private void insertFixtures() throws SQLException {
        String connectionId = scalar("""
                SELECT id::text
                FROM integration_connections
                WHERE connection_key = 'livesklad-default'
                """);
        update("""
                INSERT INTO stores (
                    id, connection_id, source_system, external_id, name
                ) VALUES
                    ('%s', '%s', 'LIVESKLAD', 'quality-store', 'Quality store'),
                    ('%s', '%s', 'LIVESKLAD',
                     'other-quality-store', 'Other quality store')
                """.formatted(
                        STORE_ID,
                        connectionId,
                        OTHER_STORE_ID,
                        connectionId
                ));
        update("""
                INSERT INTO sync_runs (
                    id, connection_id, source_system, trigger_type,
                    sync_scope, status, started_at, finished_at
                ) VALUES (
                    '%s', '%s', 'LIVESKLAD', 'MANUAL',
                    'RETURNS', 'SUCCESS', now(), now()
                )
                """.formatted(SYNC_RUN_ID, connectionId));
        insertSale(connectionId, "exact-sale", false);
        insertSale(connectionId, "latest-return", false);
        insertSale(connectionId, "deleted-sale", true);
        insertSale(connectionId, "foreign-store-scope", false);

        insertRaw(connectionId, "exact-sale", "sale", "2026-09-01 10:00:00+00");
        insertRaw(connectionId, "latest-return", "sale", "2026-09-01 10:00:00+00");
        insertRaw(connectionId, "latest-return", "saleReturn", "2026-09-01 11:00:00+00");
        insertRaw(connectionId, "deleted-sale", "sale", "2026-09-01 10:00:00+00");
        insertRaw(connectionId, "foreign-store-scope", "sale", "2026-09-01 10:00:00+00");

        insertIssue(connectionId, STORE_ID, "exact-sale");
        insertIssue(connectionId, STORE_ID, "latest-return");
        insertIssue(connectionId, STORE_ID, "deleted-sale");
        insertIssue(connectionId, OTHER_STORE_ID, "foreign-store-scope");
    }

    private void insertSale(String connectionId, String externalId, boolean deleted)
            throws SQLException {
        update("""
                INSERT INTO sales_documents (
                    connection_id, source_system, external_id, store_id,
                    document_kind, source_document_type, occurred_at,
                    business_date, net_amount, is_deleted, last_sync_run_id
                ) VALUES (
                    '%s', 'LIVESKLAD', '%s', '%s',
                    'SALE', 'sale', now(), DATE '2026-09-01', 100, %s, '%s'
                )
                """.formatted(
                        connectionId,
                        externalId,
                        STORE_ID,
                        deleted,
                        SYNC_RUN_ID
                ));
    }

    private void insertRaw(
            String connectionId,
            String externalId,
            String type,
            String firstSeenAt
    ) throws SQLException {
        update("""
                INSERT INTO raw_record_versions (
                    connection_id, store_id, source_system, entity_type,
                    external_id, payload, payload_hash, first_seen_at,
                    last_seen_at, first_sync_run_id, last_sync_run_id,
                    normalization_status
                ) VALUES (
                    '%s', '%s', 'LIVESKLAD', 'RETURN_DOCUMENT',
                    '%s', jsonb_build_object(
                        'detail', jsonb_build_object('type', '%s')
                    ), encode(digest('%s:%s', 'sha256'), 'hex'),
                    '%s', '%s', '%s', '%s', 'SKIPPED'
                )
                """.formatted(
                        connectionId,
                        STORE_ID,
                        externalId,
                        type,
                        externalId,
                        firstSeenAt,
                        firstSeenAt,
                        firstSeenAt,
                        SYNC_RUN_ID,
                        SYNC_RUN_ID
                ));
    }

    private void insertIssue(String connectionId, String storeId, String externalId)
            throws SQLException {
        update("""
                INSERT INTO data_quality_issues (
                    store_id, entity_type, entity_id, issue_code,
                    severity, status, message
                ) VALUES (
                    '%s', 'RETURN_DOCUMENT', '%s:%s',
                    'RETURN_ORIGINAL_DOCUMENT_MISSING',
                    'ERROR', 'OPEN', 'Fixture issue'
                )
                """.formatted(storeId, connectionId, externalId));
    }

    private String issueStatus(String externalId) throws SQLException {
        return scalar("""
                SELECT status
                FROM data_quality_issues
                WHERE entity_id LIKE '%%:' || '%s'
                """.formatted(externalId));
    }

    private String issueResolvedAt(String externalId) throws SQLException {
        return scalar("""
                SELECT resolved_at::text
                FROM data_quality_issues
                WHERE entity_id LIKE '%%:' || '%s'
                """.formatted(externalId));
    }

    private void rerunMigrationSql() throws IOException, SQLException {
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V53__resolve_false_sale_typed_return_issues.sql"
        )) {
            assertThat(input).isNotNull();
            update(new String(input.readAllBytes(), StandardCharsets.UTF_8));
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

    private void update(String sql) throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private String scalar(String sql) throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private String currentVersion() throws SQLException {
        return scalar("""
                SELECT version
                FROM flyway_schema_history
                WHERE success
                ORDER BY installed_rank DESC
                LIMIT 1
                """);
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );
    }
}
