package com.storeanalytics.common.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.flywaydb.core.Flyway;

/** Holds populated rollout until historical projections and the complete prospective path are rehearsed. */
public final class CatalogMigrationPreflight {
    private static final Set<String> HISTORICAL_REWRITES = Set.of(
            "56", "57", "58", "59", "60", "61", "62", "63", "64", "65",
            "66", "67", "68", "69", "70", "74", "75", "76", "77"
    );
    private static final List<String> PROTECTED_TABLES = List.of(
            "sales_document_items", "product_category_assignments"
    );

    private CatalogMigrationPreflight() {
    }

    public static void verify(Flyway flyway) {
        List<String> pending = Arrays.stream(flyway.info().pending())
                .filter(info -> info.getVersion() != null)
                .map(info -> info.getVersion().getVersion())
                .filter(CatalogMigrationPreflight::isHistoricalRewrite)
                .toList();
        if (pending.isEmpty()) {
            return;
        }
        try (Connection connection = flyway.getConfiguration().getDataSource().getConnection()) {
            connection.setReadOnly(true);
            String schema = flyway.getConfiguration().getDefaultSchema();
            if (schema == null) {
                String[] schemas = flyway.getConfiguration().getSchemas();
                schema = schemas.length == 0 ? connection.getSchema() : schemas[0];
            }
            if (schema == null || schema.isBlank()) {
                throw new IllegalStateException("Cannot verify catalog migration schema");
            }
            for (String table : PROTECTED_TABLES) {
                if (hasRows(connection, schema, table)) {
                    throw new IllegalStateException(
                            "CATALOG_PROSPECTIVE_ROLLOUT_REQUIRED: pending catalog migrations "
                                    + pending + " require a reviewed prospective projection rollout. "
                                    + "No migrations applied by this invocation. Prepare and rehearse "
                                    + "the prospective rollout; do not bypass validation or repair checksums."
                    );
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot verify catalog history before migration", exception);
        }
    }

    static boolean isHistoricalRewrite(String version) {
        return HISTORICAL_REWRITES.contains(version);
    }

    private static boolean hasRows(Connection connection, String schema, String table) throws SQLException {
        String qualified = "\"" + schema.replace("\"", "\"\"") + "\".\"" + table + "\"";
        try (var statement = connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            statement.setQueryTimeout(10);
            statement.setString(1, qualified);
            try (var rows = statement.executeQuery()) {
                rows.next();
                if (!rows.getBoolean(1)) {
                    return false;
                }
            }
        }
        try (var statement = connection.prepareStatement("SELECT EXISTS (SELECT 1 FROM " + qualified + ")")) {
            statement.setQueryTimeout(10);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getBoolean(1);
            }
        }
    }
}
