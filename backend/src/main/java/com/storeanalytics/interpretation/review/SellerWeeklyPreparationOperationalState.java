package com.storeanalytics.interpretation.review;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Read-only, bounded-label diagnostics. Source waits are not technical failures. */
@Repository
public class SellerWeeklyPreparationOperationalState {
    private final JdbcTemplate jdbc;

    public SellerWeeklyPreparationOperationalState(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true, timeout = 30)
    public Counts read(Instant now) {
        return jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE status = 'PENDING') AS pending,
                    count(*) FILTER (WHERE status = 'RUNNING') AS running,
                    count(*) FILTER (WHERE status = 'WAITING_SOURCES') AS waiting_sources,
                    count(*) FILTER (WHERE status = 'WAITING_HISTORY') AS waiting_history,
                    count(*) FILTER (WHERE status = 'FAILED') AS failed,
                    count(*) FILTER (WHERE status = 'SUCCEEDED') AS succeeded,
                    count(*) FILTER (WHERE status = 'RUNNING' AND lease_until <= ?) AS expired_lease,
                    count(*) FILTER (WHERE status IN ('PENDING','RUNNING','WAITING_SOURCES','WAITING_HISTORY')
                        AND created_at < ?::timestamptz - interval '6 hours') AS delayed
                FROM seller_weekly_preparation_jobs
                """, (row, index) -> new Counts(row.getLong("pending"), row.getLong("running"),
                row.getLong("waiting_sources"), row.getLong("waiting_history"), row.getLong("failed"),
                row.getLong("succeeded"), row.getLong("expired_lease"), row.getLong("delayed")),
                Timestamp.from(now), Timestamp.from(now));
    }

    public record Counts(long pending, long running, long waitingSources, long waitingHistory,
                         long failed, long succeeded, long expiredLease, long delayed) { }
}
