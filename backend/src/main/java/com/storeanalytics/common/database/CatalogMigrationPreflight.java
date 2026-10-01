package com.storeanalytics.common.database;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.LinkedHashMap;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.flywaydb.core.Flyway;

/** Verifies the reviewed prospective rollout before migration and historical rows afterwards. */
public final class CatalogMigrationPreflight {
    private static final Set<String> HISTORICAL_REWRITES = Set.of(
            "56", "57", "58", "59", "60", "61", "62", "63", "64", "65",
            "66", "67", "68", "69", "70", "74", "75", "76", "77"
    );
    private static final List<String> PROTECTED_TABLES = List.of(
            "sales_document_items", "product_category_assignments",
            "product_payroll_category_assignments"
    );

    // Exact reviewed SQL bytes. Changing any draft migration requires a new rehearsal and code review.
    private static final Map<String, String> REVIEWED_SQL_SHA256 = Map.ofEntries(
            Map.entry("V56__split_speaker_category.sql",
                    "013ab47def886cc08e29552580ba9140f3e7103fae22930f3e02bc484b24894a"),
            Map.entry("V57__split_fitness_wearable_category.sql",
                    "dca4f02b0d8ed5aff3c1de6c49a615b5b38661f0c43fe1bcc6626d2ad5c13612"),
            Map.entry("V58__split_smart_glasses_category.sql",
                    "85878be796f383f33d358c3b213386da3f711c920f6eda95b7f963ef524485a3"),
            Map.entry("V59__split_camera_category.sql",
                    "2c4c53751cb345847ee175f07802571dab23c089548bc8f2230da1776730b64f"),
            Map.entry("V60__classify_setup_products.sql",
                    "2c9282a1a2a427d5d23a4cce7f20a5af11878ab82c2cbac5a18be3d69d0f026f"),
            Map.entry("V61__split_hair_styler_category.sql",
                    "b8a1562db537c83a48ba4a5581169d2864d2f283c63e04d8d86c791822cd90e0"),
            Map.entry("V62__split_headphone_categories.sql",
                    "7292793566ffa9d4c943cca4c3c2c62b70133948929d389e01417251a3242783"),
            Map.entry("V63__correct_iphone_camera_glass.sql",
                    "2c9282a1a2a427d5d23a4cce7f20a5af11878ab82c2cbac5a18be3d69d0f026f"),
            Map.entry("V64__correct_x_crystal_iphone_cases.sql",
                    "2c9282a1a2a427d5d23a4cce7f20a5af11878ab82c2cbac5a18be3d69d0f026f"),
            Map.entry("V65__split_ipad_mac_and_other_device_cases.sql",
                    "642ffd4eaffb99807fbaa42be5ee2740d8643ff620d3ad1aec6460eb44c0a69f"),
            Map.entry("V66__correct_mago_pro_iphone_cases.sql",
                    "2c9282a1a2a427d5d23a4cce7f20a5af11878ab82c2cbac5a18be3d69d0f026f"),
            Map.entry("V67__correct_mago_15_16_iphone_cases.sql",
                    "2c9282a1a2a427d5d23a4cce7f20a5af11878ab82c2cbac5a18be3d69d0f026f"),
            Map.entry("V68__correct_remaining_model_named_keephone_iphone_cases.sql",
                    "2c9282a1a2a427d5d23a4cce7f20a5af11878ab82c2cbac5a18be3d69d0f026f"),
            Map.entry("V69__classify_explicit_iphone_cases.sql",
                    "2c9282a1a2a427d5d23a4cce7f20a5af11878ab82c2cbac5a18be3d69d0f026f"),
            Map.entry("V70__add_unresolved_phone_case_category.sql",
                    "f76c398f7304a2dfb51a54f50e195cac27da68c869aec104944628a7e9ead1a0"),
            Map.entry("V74__correct_confirmed_charging_products.sql",
                    "2c9282a1a2a427d5d23a4cce7f20a5af11878ab82c2cbac5a18be3d69d0f026f"),
            Map.entry("V75__split_power_bank_category_and_attach_rate.sql",
                    "a513b7fd92901fa65deea2a3a836edc5ce67bcb488c036946ea4993a089b925b"),
            Map.entry("V76__classify_hubs_and_adapters.sql",
                    "24ec591f8168b9ff5daa1667d34b02c5b1a0ad701653cde60059ebe0f513ea80"),
            Map.entry("V77__classify_charging_stations_and_trackers.sql",
                    "2c9282a1a2a427d5d23a4cce7f20a5af11878ab82c2cbac5a18be3d69d0f026f")
    );

    private CatalogMigrationPreflight() {
    }

    /** A populated rollout is allowed only for the frozen SQL set and an explicit future cutover. */
    public static Map<String, String> prepareReviewedRollout(Flyway flyway, Instant boundary) {
        List<String> pending = Arrays.stream(flyway.info().pending())
                .filter(info -> info.getVersion() != null)
                .map(info -> info.getVersion().getVersion())
                .filter(CatalogMigrationPreflight::isHistoricalRewrite)
                .toList();
        if (pending.isEmpty()) {
            return Map.of();
        }
        try (Connection connection = flyway.getConfiguration().getDataSource().getConnection()) {
            connection.setReadOnly(true);
            String schema = schema(flyway, connection);
            boolean populated = false;
            for (String table : PROTECTED_TABLES) {
                populated |= hasRows(connection, schema, table);
            }
            if (!populated) {
                return Map.of();
            }
            CatalogActivationState.requireBusinessDayBoundary(boundary);
            if (boundary == null || !boundary.isAfter(Instant.now().plusSeconds(3600))) {
                throw new IllegalStateException("CATALOG_PROSPECTIVE_ROLLOUT_REQUIRED: "
                        + "explicit business-day activation must be more than one hour in the future");
            }
            verifyReviewedSql();
            return fingerprints(connection, schema);
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot verify catalog history before migration", exception);
        }
    }

    /** After migration, stop release if any protected historical row or assignment changed. */
    public static void verifyUnchanged(Flyway flyway, Map<String, String> before) {
        if (before.isEmpty()) {
            return;
        }
        try (Connection connection = flyway.getConfiguration().getDataSource().getConnection()) {
            connection.setReadOnly(true);
            if (!before.equals(fingerprints(connection, schema(flyway, connection)))) {
                throw new IllegalStateException("CATALOG_HISTORICAL_ROWS_CHANGED: "
                        + "migration stopped before activation; keep writers stopped and investigate");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot verify catalog history after migration", exception);
        }
    }

    private static void verifyReviewedSql() {
        for (var entry : REVIEWED_SQL_SHA256.entrySet()) {
            String name = entry.getKey();
            try (InputStream stream = CatalogMigrationPreflight.class.getClassLoader()
                    .getResourceAsStream("db/migration/" + name)) {
                if (stream == null) {
                    throw new IllegalStateException("Missing reviewed catalog migration: " + name);
                }
                String actual = HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()));
                if (!entry.getValue().equals(actual)) {
                    throw new IllegalStateException("CATALOG_MIGRATION_SQL_DRIFT: " + name);
                }
            } catch (IOException | NoSuchAlgorithmException exception) {
                throw new IllegalStateException("Cannot verify reviewed catalog migration: " + name, exception);
            }
        }
    }

    private static String schema(Flyway flyway, Connection connection) throws SQLException {
        String schema = flyway.getConfiguration().getDefaultSchema();
        if (schema == null) {
            String[] schemas = flyway.getConfiguration().getSchemas();
            schema = schemas.length == 0 ? connection.getSchema() : schemas[0];
        }
        if (schema == null || schema.isBlank()) {
            throw new IllegalStateException("Cannot verify catalog migration schema");
        }
        return schema;
    }

    private static Map<String, String> fingerprints(Connection connection, String schema)
            throws SQLException {
        Map<String, String> result = new LinkedHashMap<>();
        for (String table : PROTECTED_TABLES) {
            String qualified = "\"" + schema.replace("\"", "\"\"") + "\".\"" + table + "\"";
            String query = "SELECT count(*), md5(COALESCE(string_agg(md5(to_jsonb(t)::text), '' "
                    + "ORDER BY t.id), '')) FROM " + qualified + " t";
            try (var statement = connection.prepareStatement(query)) {
                statement.setQueryTimeout(120);
                try (var rows = statement.executeQuery()) {
                    rows.next();
                    result.put(table, rows.getLong(1) + ":" + rows.getString(2));
                }
            }
        }
        return Map.copyOf(result);
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
