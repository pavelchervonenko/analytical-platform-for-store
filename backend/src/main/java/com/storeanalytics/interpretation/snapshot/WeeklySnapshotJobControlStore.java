package com.storeanalytics.interpretation.snapshot;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class WeeklySnapshotJobControlStore {

    private final JdbcTemplate jdbcTemplate;

    public WeeklySnapshotJobControlStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public Optional<UUID> heartbeatOwned(
            String owner,
            Duration leaseDuration,
            Instant now
    ) {
        String leaseOwner = requireText(owner, "owner");
        Duration lease = positive(leaseDuration, "leaseDuration");
        Instant timestamp = requireNonNull(now, "now");
        List<UUID> renewed = jdbcTemplate.query(
                """
                WITH candidate AS (
                    SELECT id FROM analytics_snapshot_jobs
                    WHERE status = 'RUNNING' AND lease_owner = ?
                      AND lease_until > ?
                    ORDER BY lease_until
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE analytics_snapshot_jobs job
                SET lease_until = ?, version = version + 1
                FROM candidate
                WHERE job.id = candidate.id
                RETURNING job.id
                """,
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                leaseOwner,
                Timestamp.from(timestamp),
                Timestamp.from(timestamp.plus(lease))
        );
        return renewed.isEmpty() ? Optional.empty() : Optional.of(renewed.getFirst());
    }

    @Transactional(readOnly = true)
    public boolean cancellationRequested(UUID jobId) {
        UUID validatedJobId = requireNonNull(jobId, "jobId");
        List<Boolean> values = jdbcTemplate.query(
                """
                SELECT cancel_requested FROM analytics_snapshot_jobs WHERE id = ?
                """,
                (resultSet, rowNumber) -> resultSet.getBoolean("cancel_requested"),
                validatedJobId
        );
        if (values.isEmpty()) {
            throw new IllegalArgumentException(
                    "Weekly snapshot job does not exist: " + validatedJobId
            );
        }
        return values.getFirst();
    }

    private Duration positive(Duration value, String field) {
        Duration duration = requireNonNull(value, field);
        require(!duration.isZero() && !duration.isNegative(), field + " must be positive");
        return duration;
    }
}
