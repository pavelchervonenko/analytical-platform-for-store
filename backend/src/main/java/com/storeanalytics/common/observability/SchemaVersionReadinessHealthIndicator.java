package com.storeanalytics.common.observability;

import com.storeanalytics.common.database.ExpectedSchemaVersion;
import com.storeanalytics.common.database.SchemaHistoryEntry;
import com.storeanalytics.common.database.SchemaHistoryRepository;
import java.util.Optional;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

@Component("schemaVersionReadiness")
public class SchemaVersionReadinessHealthIndicator implements HealthIndicator {

    private final SchemaHistoryRepository schemaHistoryRepository;
    private final ExpectedSchemaVersion expectedSchemaVersion;

    public SchemaVersionReadinessHealthIndicator(
            SchemaHistoryRepository schemaHistoryRepository,
            ExpectedSchemaVersion expectedSchemaVersion
    ) {
        this.schemaHistoryRepository = schemaHistoryRepository;
        this.expectedSchemaVersion = expectedSchemaVersion;
    }

    @Override
    public Health health() {
        String expectedVersion = expectedSchemaVersion.value();
        try {
            Optional<SchemaHistoryEntry> latest = schemaHistoryRepository
                    .findLatestVersionedMigration();
            if (latest.isEmpty()) {
                return Health.down()
                        .withDetail("reason", "SCHEMA_NOT_INITIALIZED")
                        .withDetail("expectedVersion", expectedVersion)
                        .build();
            }
            SchemaHistoryEntry actual = latest.orElseThrow();
            if (!actual.successful()) {
                return Health.down()
                        .withDetail("reason", "SCHEMA_MIGRATION_FAILED")
                        .withDetail("expectedVersion", expectedVersion)
                        .withDetail("actualVersion", actual.version())
                        .build();
            }
            if (!expectedVersion.equals(actual.version())) {
                return Health.down()
                        .withDetail("reason", "SCHEMA_VERSION_MISMATCH")
                        .withDetail("expectedVersion", expectedVersion)
                        .withDetail("actualVersion", actual.version())
                        .build();
            }
            return Health.up()
                    .withDetail("schemaVersion", actual.version())
                    .build();
        } catch (DataAccessException exception) {
            return Health.down()
                    .withDetail("reason", "SCHEMA_HISTORY_UNAVAILABLE")
                    .withDetail("expectedVersion", expectedVersion)
                    .build();
        }
    }
}
