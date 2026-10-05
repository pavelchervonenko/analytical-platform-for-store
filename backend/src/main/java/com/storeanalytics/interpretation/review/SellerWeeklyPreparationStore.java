package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Deterministic free backlog. Discovery/claims never enqueue AI or infer membership history. */
@Repository
public class SellerWeeklyPreparationStore {
    private static final String CLAIM_SQL = """
            WITH candidate AS (
                SELECT job.id FROM seller_weekly_preparation_jobs job
                JOIN stores store ON store.id = job.store_id AND store.is_active AND store.timezone = job.timezone
                JOIN seller_weekly_backlog_state state ON state.store_id = job.store_id
                JOIN store_seller_membership_state history ON history.store_id = job.store_id
                    AND history.authoritative_from = state.authoritative_from
                WHERE state.timezone = job.timezone AND job.next_evaluation_at <= ?
                  AND (job.status IN ('PENDING','WAITING_SOURCES','WAITING_HISTORY')
                       OR (job.status = 'RUNNING' AND job.lease_until <= ?))
                  AND (?::timestamptz AT TIME ZONE job.timezone)::date >= job.period_end + 1
                ORDER BY CASE WHEN job.status = 'RUNNING' THEN job.lease_until ELSE job.next_evaluation_at END,
                    job.period_start, job.id FOR UPDATE OF job SKIP LOCKED LIMIT 1
            )
            UPDATE seller_weekly_preparation_jobs job SET status = 'RUNNING', lease_owner = ?, lease_token = ?,
                lease_until = ?, preparation_attempt_count = job.preparation_attempt_count + 1, updated_at = ?
            FROM candidate WHERE job.id = candidate.id RETURNING job.*
            """;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public SellerWeeklyPreparationStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = requireNonNull(clock, "clock");
    }

    /** At most one bounded page per store; cursor survives restarts and arbitrarily old gaps. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 30)
    public Discovery discover(UUID storeId, Instant now, int maximumWeeks) {
        requireNonNull(storeId, "storeId");
        requireNonNull(now, "now");
        require(maximumWeeks >= 1 && maximumWeeks <= 52, "Discovery page must contain 1..52 weeks");
        List<Baseline> baselines = jdbc.query("""
                SELECT store.timezone, history.authoritative_from FROM stores store
                JOIN store_seller_membership_state history ON history.store_id = store.id
                WHERE store.id = ? AND store.is_active FOR SHARE OF store, history
                """, (row, index) -> new Baseline(row.getString("timezone"),
                row.getTimestamp("authoritative_from").toInstant()), storeId);
        if (baselines.isEmpty()) {
            return new Discovery(0, "BASELINE_OR_ACTIVE_STORE_MISSING", null);
        }
        Baseline baseline = baselines.getFirst();
        ZoneId zone = ZoneId.of(baseline.timezone());
        LocalDate firstMonday = LocalDate.ofInstant(baseline.from(), zone)
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        if (firstMonday.atStartOfDay(zone).toInstant().isBefore(baseline.from())) {
            firstMonday = firstMonday.plusWeeks(1);
        }
        // The first comparison's PREVIOUS week, not merely its current week, must be authoritative.
        LocalDate firstPeriod = firstMonday.plusWeeks(1);
        jdbc.update("""
                INSERT INTO seller_weekly_backlog_state(store_id, authoritative_from, timezone,
                    next_period_start, updated_at) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING
                """, storeId, stamp(baseline.from()), baseline.timezone(), firstPeriod, stamp(now));
        Cursor cursor = jdbc.queryForObject("""
                SELECT authoritative_from, timezone, next_period_start FROM seller_weekly_backlog_state
                WHERE store_id = ? FOR UPDATE
                """, (row, index) -> new Cursor(row.getTimestamp("authoritative_from").toInstant(),
                row.getString("timezone"), row.getObject("next_period_start", LocalDate.class)), storeId);
        if (cursor == null || !cursor.baseline().equals(baseline.from())
                || !cursor.timezone().equals(baseline.timezone()) || cursor.next().isBefore(firstPeriod)) {
            throw new IllegalStateException("BACKLOG_CONFIGURATION_CHANGED");
        }
        LocalDate latest = ClosedSellerWeek.latest(now, baseline.timezone()).start();
        LocalDate next = cursor.next();
        int scanned = 0;
        int inserted = 0;
        while (!next.isAfter(latest) && scanned < maximumWeeks) {
            inserted += jdbc.update("""
                    INSERT INTO seller_weekly_preparation_jobs(store_id,period_start,period_end,timezone,
                        status,next_evaluation_at,created_at,updated_at) VALUES (?,?,?,?,'PENDING',?,?,?)
                    ON CONFLICT (store_id,period_start) DO NOTHING
                    """, storeId, next, next.plusDays(6), baseline.timezone(), stamp(now), stamp(now), stamp(now));
            next = next.plusWeeks(1);
            scanned++;
        }
        jdbc.update("UPDATE seller_weekly_backlog_state SET next_period_start = ?, updated_at = ? WHERE store_id = ?",
                next, stamp(now), storeId);
        return new Discovery(inserted, next.isAfter(latest) ? "CAUGHT_UP" : "MORE_PAGES", next);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 30)
    public Optional<Claim> claimNext(String owner, Duration lease, Instant now) {
        String selectedOwner = requireText(owner, "owner");
        require(selectedOwner.length() <= 120, "owner exceeds 120 characters");
        duration(lease, Duration.ofSeconds(15), Duration.ofMinutes(10));
        requireNonNull(now, "now");
        Instant evaluatedAt = evaluationTime(now);
        UUID token = UUID.randomUUID();
        return jdbc.query(CLAIM_SQL, (row, index) -> claim(row),
                stamp(evaluatedAt), stamp(evaluatedAt), stamp(evaluatedAt),
                selectedOwner, token, stamp(evaluatedAt.plus(lease)), stamp(evaluatedAt)).stream().findFirst();
    }

    /** Reopen free preparation, not the paid job, when a previously bound snapshot becomes stale. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 30)
    public int requeueStaleSnapshots(int maximumJobs, Instant now) {
        require(maximumJobs >= 1 && maximumJobs <= 100, "Refresh page must contain 1..100 jobs");
        requireNonNull(now, "now");
        return jdbc.update("""
                WITH stale AS (
                    SELECT job.id FROM seller_weekly_preparation_jobs job
                    JOIN stores store ON store.id = job.store_id AND store.is_active AND store.timezone = job.timezone
                    JOIN seller_weekly_backlog_state state ON state.store_id = job.store_id
                    JOIN store_seller_membership_state history ON history.store_id = job.store_id
                        AND history.authoritative_from = state.authoritative_from
                    JOIN weekly_review_snapshots saved ON saved.id = job.snapshot_id
                    CROSS JOIN LATERAL (
                        SELECT (?::timestamptz AT TIME ZONE job.timezone)::date AS local_day
                    ) calendar
                    WHERE job.status = 'SUCCEEDED' AND state.timezone = job.timezone AND (NOT EXISTS (
                        SELECT 1 FROM weekly_review_generation_state checkpoint
                        JOIN store_analytics_source_state source ON source.store_id = checkpoint.store_id
                        WHERE checkpoint.store_id = job.store_id AND checkpoint.period_start = job.period_start
                          AND checkpoint.period_end = job.period_end AND checkpoint.target_contract_version = 3
                          AND checkpoint.compatible_snapshot_id = job.snapshot_id
                          AND checkpoint.outcome IN ('CREATED','REUSED')
                          AND checkpoint.last_evaluated_source_revision = source.revision
                          AND NOT EXISTS (SELECT 1 FROM weekly_review_snapshots later
                              WHERE later.store_id = job.store_id AND later.period_start = job.period_start
                                AND later.period_end = job.period_end AND later.revision > (
                                    SELECT revision FROM weekly_review_snapshots WHERE id = job.snapshot_id)))
                        OR (job.period_start < calendar.local_day
                                - (extract(isodow FROM calendar.local_day)::integer - 1) - 7
                            AND (jsonb_array_length(coalesce(saved.report_payload->'actions','[]'::jsonb)) > 0
                                OR EXISTS (SELECT 1 FROM jsonb_array_elements(
                                    coalesce(saved.report_payload->'employees','[]'::jsonb)) employee
                                    WHERE employee #> '{card,action}' IS NOT NULL
                                      AND employee #> '{card,action}' <> 'null'::jsonb))))
                    ORDER BY job.period_start, job.id FOR UPDATE OF job SKIP LOCKED LIMIT ?
                )
                UPDATE seller_weekly_preparation_jobs job SET status = 'PENDING', snapshot_id = NULL,
                    last_reason_code = 'SNAPSHOT_NOT_CURRENT', next_evaluation_at = ?, updated_at = ?
                FROM stale WHERE job.id = stale.id
                """, stamp(evaluationTime(now)), maximumJobs, stamp(now), stamp(now));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 30)
    public boolean heartbeat(Claim claim, Duration lease, Instant now) {
        requireNonNull(claim, "claim");
        duration(lease, Duration.ofSeconds(15), Duration.ofMinutes(10));
        requireNonNull(now, "now");
        if (!lockOwnedJob(claim)) {
            return false;
        }
        Instant evaluatedAt = evaluationTime(now);
        return jdbc.update("""
                UPDATE seller_weekly_preparation_jobs SET lease_until = greatest(lease_until,?), updated_at = ?
                WHERE id = ? AND status = 'RUNNING' AND lease_token = ? AND lease_owner = ? AND lease_until > ?
                """, stamp(evaluatedAt.plus(lease)), stamp(evaluatedAt), claim.id(), claim.token(),
                claim.owner(), stamp(evaluatedAt)) == 1;
    }

    /** Source/history waits are free and delayed; a technical failure is terminal, not a busy retry loop. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 30)
    public boolean defer(Claim claim, Deferral deferral, String reason, Duration delay, Instant now) {
        requireNonNull(claim, "claim");
        requireNonNull(deferral, "deferral");
        require(reason != null && reason.matches("[A-Z][A-Z0-9_]{0,79}"), "Invalid sanitized reason code");
        duration(delay, Duration.ofMinutes(1), Duration.ofDays(1));
        requireNonNull(now, "now");
        if (!lockOwnedJob(claim)) {
            return false;
        }
        Instant evaluatedAt = evaluationTime(now);
        return jdbc.update("""
                UPDATE seller_weekly_preparation_jobs SET status = ?, next_evaluation_at = ?, last_reason_code = ?,
                    lease_owner = NULL, lease_token = NULL, lease_until = NULL, updated_at = ?
                WHERE id = ? AND status = 'RUNNING' AND lease_token = ? AND lease_owner = ? AND lease_until > ?
                """, deferral.name(), stamp(evaluatedAt.plus(delay)), reason, stamp(evaluatedAt), claim.id(),
                claim.token(), claim.owner(), stamp(evaluatedAt)) == 1;
    }

    /** Bind only a historical seller snapshot for this exact store/week; this is not AI publication. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 30)
    public boolean completeWithSnapshot(Claim claim, UUID snapshotId, Instant now) {
        requireNonNull(claim, "claim");
        requireNonNull(snapshotId, "snapshotId");
        requireNonNull(now, "now");
        // Match snapshot-writer lock order (store -> source -> job), keeping freshness at binding.
        List<UUID> stores = jdbc.queryForList("""
                SELECT store.id FROM stores store
                WHERE store.id = ? AND store.is_active AND store.timezone = ? FOR SHARE
                """, UUID.class, claim.storeId(), claim.timezone());
        if (stores.isEmpty() || jdbc.queryForList("""
                SELECT store_id FROM store_analytics_source_state WHERE store_id = ? FOR SHARE
                """, UUID.class, claim.storeId()).isEmpty()) {
            return false;
        }
        if (!lockOwnedJob(claim)) {
            return false;
        }
        Instant evaluatedAt = evaluationTime(now);
        return jdbc.update("""
                UPDATE seller_weekly_preparation_jobs job SET status = 'SUCCEEDED', snapshot_id = ?,
                    lease_owner = NULL, lease_token = NULL, lease_until = NULL, last_reason_code = NULL, updated_at = ?
                WHERE job.id = ? AND job.store_id = ? AND job.status = 'RUNNING' AND job.lease_token = ?
                  AND job.lease_owner = ? AND job.lease_until > ? AND EXISTS (
                    SELECT 1 FROM weekly_review_snapshots snapshot
                    JOIN weekly_review_generation_state checkpoint ON checkpoint.compatible_snapshot_id = snapshot.id
                    JOIN store_analytics_source_state source ON source.store_id = snapshot.store_id
                    JOIN stores store ON store.id = snapshot.store_id AND store.is_active
                    WHERE snapshot.id = ? AND snapshot.store_id = job.store_id
                      AND snapshot.period_start = job.period_start AND snapshot.period_end = job.period_end
                      AND snapshot.timezone = job.timezone AND store.timezone = job.timezone
                      AND snapshot.report_contract_version = 3 AND snapshot.report_scope = 'SELLERS'
                      AND snapshot.report_state IN ('READY','PARTIAL')
                      AND snapshot.report_payload #>> '{membership,basis}' = 'HISTORICAL_DOCUMENT_MEMBERSHIP_V1'
                      AND checkpoint.store_id = job.store_id AND checkpoint.period_start = job.period_start
                      AND checkpoint.period_end = job.period_end AND checkpoint.target_contract_version = 3
                      AND checkpoint.outcome IN ('CREATED','REUSED')
                      AND checkpoint.last_evaluated_source_revision = source.revision
                      AND NOT EXISTS (SELECT 1 FROM weekly_review_snapshots later
                          WHERE later.store_id = snapshot.store_id AND later.period_start = snapshot.period_start
                            AND later.period_end = snapshot.period_end AND later.revision > snapshot.revision))
                """, snapshotId, stamp(evaluatedAt), claim.id(), claim.storeId(), claim.token(),
                claim.owner(), stamp(evaluatedAt), snapshotId) == 1;
    }

    private boolean lockOwnedJob(Claim claim) {
        return !jdbc.queryForList("""
                SELECT id FROM seller_weekly_preparation_jobs
                WHERE id = ? AND store_id = ? AND status = 'RUNNING' AND lease_token = ? AND lease_owner = ?
                FOR UPDATE
                """, UUID.class, claim.id(), claim.storeId(), claim.token(), claim.owner()).isEmpty();
    }

    private Instant evaluationTime(Instant requestedAt) {
        Instant observedAt = clock.instant();
        return requestedAt.isAfter(observedAt) ? requestedAt : observedAt;
    }

    private static Claim claim(ResultSet row) throws SQLException {
        return new Claim(row.getObject("id", UUID.class), row.getObject("store_id", UUID.class),
                row.getObject("period_start", LocalDate.class), row.getString("timezone"), row.getString("lease_owner"),
                row.getObject("lease_token", UUID.class), row.getInt("preparation_attempt_count"),
                row.getTimestamp("lease_until").toInstant());
    }

    private static void duration(Duration value, Duration minimum, Duration maximum) {
        requireNonNull(value, "duration");
        require(value.compareTo(minimum) >= 0 && value.compareTo(maximum) <= 0, "Duration outside safe bounds");
    }

    private static Timestamp stamp(Instant value) {
        return Timestamp.from(value);
    }

    public enum Deferral { WAITING_SOURCES, WAITING_HISTORY, FAILED }
    public record Discovery(int insertedWeeks, String reasonCode, LocalDate nextPeriodStart) { }
    public record Claim(UUID id, UUID storeId, LocalDate periodStart, String timezone, String owner,
            UUID token, int attemptCount, Instant leaseUntil) { }
    private record Baseline(String timezone, Instant from) { }
    private record Cursor(Instant baseline, String timezone, LocalDate next) { }
}
