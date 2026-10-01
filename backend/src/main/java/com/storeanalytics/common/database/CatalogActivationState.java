package com.storeanalytics.common.database;

import java.sql.Timestamp;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import org.springframework.jdbc.core.JdbcTemplate;

/** Migration-only registration and read-only runtime agreement; no mutable date fallback. */
public final class CatalogActivationState {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Europe/Kaliningrad");

    private CatalogActivationState() { }

    public static Instant requireBusinessDayBoundary(Instant instant) {
        if (instant != null && !instant.atZone(BUSINESS_ZONE).toLocalTime().equals(LocalTime.MIDNIGHT)) {
            throw new IllegalArgumentException("Catalog activation must start at business-day midnight");
        }
        return instant;
    }

    public static Instant parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Instant instant = Instant.parse(value);
        if (instant.getNano() % 1000 != 0) {
            throw new IllegalArgumentException("Catalog activation must have PostgreSQL microsecond precision");
        }
        return instant;
    }

    public static void register(Flyway flyway, Instant expected) {
        try (var connection = flyway.getConfiguration().getDataSource().getConnection()) {
            String schema = flyway.getConfiguration().getDefaultSchema();
            if (schema == null && flyway.getConfiguration().getSchemas().length > 0) {
                schema = flyway.getConfiguration().getSchemas()[0];
            }
            if (schema != null) {
                connection.setSchema(schema);
            }
            register(new JdbcTemplate(new SingleConnectionDataSource(connection, true)), expected);
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot register catalog activation in migration schema", exception);
        }
    }

    public static void register(JdbcTemplate jdbc, Instant expected) {
        requireBusinessDayBoundary(expected);
        if (expected != null) {
            jdbc.update("""
                    INSERT INTO catalog_classification_activation(singleton, activate_from)
                    SELECT true, ?::timestamptz WHERE ?::timestamptz >= clock_timestamp()
                    ON CONFLICT (singleton) DO NOTHING
                    """, Timestamp.from(expected), Timestamp.from(expected));
        }
        verify(jdbc, expected);
    }

    public static void verify(JdbcTemplate jdbc, Instant expected) {
        var dates = jdbc.query("SELECT activate_from, policy_version FROM catalog_classification_activation",
                (row, index) -> {
                    if (!"catalog-prospective-v1".equals(row.getString("policy_version"))) {
                        throw new IllegalStateException("Unsupported catalog activation policy");
                    }
                    return row.getTimestamp("activate_from").toInstant();
                });
        if (dates.isEmpty() && expected == null) {
            return; // Unactivated development/fresh-schema state; not prospective rollout approval.
        }
        if (dates.size() != 1 || expected == null || !dates.getFirst().equals(expected)) {
            throw new IllegalStateException("CATALOG_ACTIVATION_MISMATCH: explicit configuration must match "
                    + "the immutable database boundary; never repair or overwrite it to start the application");
        }
    }
}
