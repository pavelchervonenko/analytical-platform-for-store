package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Legacy v2 invalidation uses the DB marker observed with facts, never application wall-clock time. */
@Repository
public class WeeklyReviewAttributionRepository {

    private final JdbcTemplate jdbc;

    public WeeklyReviewAttributionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Optional<Instant> observe(UUID storeId) {
        return jdbc.query("SELECT changed_at FROM attach_attribution_changes WHERE store_id = ?",
                (row, index) -> row.getTimestamp("changed_at").toInstant(),
                requireNonNull(storeId, "storeId")).stream().findFirst();
    }

    @Transactional(readOnly = true)
    public boolean changed(UUID storeId, UUID snapshotId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM attach_attribution_changes change
                    LEFT JOIN attach_snapshot_checks checked
                        ON checked.store_id = change.store_id AND checked.snapshot_id = ?
                    WHERE change.store_id = ? AND change.changed_at IS DISTINCT FROM checked.checked_through
                )
                """, Boolean.class, requireNonNull(snapshotId, "snapshotId"),
                requireNonNull(storeId, "storeId")));
    }

    @Transactional
    public void acknowledge(UUID storeId, UUID snapshotId, Optional<Instant> observedChange) {
        Optional<Instant> observed = requireNonNull(observedChange, "observedChange");
        jdbc.update("""
                INSERT INTO attach_snapshot_checks (store_id, snapshot_id, checked_through)
                VALUES (?, ?, COALESCE(?::timestamptz, '-infinity'::timestamptz))
                ON CONFLICT (store_id, snapshot_id) DO UPDATE
                    SET checked_through = EXCLUDED.checked_through
                """, requireNonNull(storeId, "storeId"), requireNonNull(snapshotId, "snapshotId"),
                observed.map(Timestamp::from).orElse(null));
    }
}
