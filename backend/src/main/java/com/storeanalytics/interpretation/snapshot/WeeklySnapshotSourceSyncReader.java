package com.storeanalytics.interpretation.snapshot;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class WeeklySnapshotSourceSyncReader {

    private final JdbcTemplate jdbcTemplate;

    public WeeklySnapshotSourceSyncReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public Instant completedAt(UUID storeId, UUID sourceSyncJobId) {
        UUID validatedStoreId = requireNonNull(storeId, "storeId");
        UUID validatedSyncJobId = requireNonNull(sourceSyncJobId, "sourceSyncJobId");
        List<Instant> values = jdbcTemplate.query(
                """
                SELECT job.finished_at
                FROM stores store
                JOIN sync_jobs job ON job.connection_id = store.connection_id
                WHERE store.id = ? AND job.id = ? AND job.status = 'SUCCESS'
                  AND job.finished_at IS NOT NULL
                """,
                (resultSet, rowNumber) -> {
                    Timestamp value = resultSet.getTimestamp("finished_at");
                    return value.toInstant();
                },
                validatedStoreId,
                validatedSyncJobId
        );
        if (values.isEmpty()) {
            throw new IllegalStateException(
                    "Snapshot source sync is no longer a completed successful job"
            );
        }
        return values.getFirst();
    }
}
