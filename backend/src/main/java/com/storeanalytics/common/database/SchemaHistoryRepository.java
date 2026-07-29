package com.storeanalytics.common.database;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SchemaHistoryRepository {

    private static final String LATEST_VERSION_SQL = """
            SELECT version, success
            FROM flyway_schema_history
            WHERE version IS NOT NULL
            ORDER BY installed_rank DESC
            LIMIT 1
            """;

    private final JdbcTemplate jdbcTemplate;

    public SchemaHistoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<SchemaHistoryEntry> findLatestVersionedMigration() {
        List<SchemaHistoryEntry> entries = jdbcTemplate.query(
                LATEST_VERSION_SQL,
                (resultSet, rowNumber) -> new SchemaHistoryEntry(
                        resultSet.getString("version"),
                        resultSet.getBoolean("success")
                )
        );
        return entries.stream().findFirst();
    }
}
