package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Bounded metadata discovery only; the exact historical reader and writable fence still authorize enqueue. */
@Repository
public class SellerWeeklyAutomaticAiCandidates {
    private final JdbcTemplate jdbc;

    public SellerWeeklyAutomaticAiCandidates(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, timeout = 30)
    public List<Candidate> readyAfter(Candidate cursor, int maximum, Instant now) {
        require(maximum >= 1 && maximum <= 100, "AI candidate page must contain 1..100 periods");
        requireNonNull(now, "now");
        return jdbc.query("""
                SELECT preparation.id,preparation.store_id,preparation.period_start,preparation.snapshot_id
                FROM seller_weekly_preparation_jobs preparation
                JOIN stores store ON store.id=preparation.store_id AND store.is_active
                    AND store.timezone=preparation.timezone
                JOIN seller_weekly_backlog_state backlog ON backlog.store_id=store.id
                    AND backlog.timezone=preparation.timezone
                JOIN store_seller_membership_state history ON history.store_id=store.id
                    AND history.authoritative_from=backlog.authoritative_from
                JOIN weekly_review_snapshots saved ON saved.id=preparation.snapshot_id
                    AND saved.store_id=store.id AND saved.period_start=preparation.period_start
                    AND saved.period_end=preparation.period_end AND saved.timezone=preparation.timezone
                    AND saved.report_contract_version=3
                WHERE preparation.status='SUCCEEDED' AND saved.report_state IN ('READY','PARTIAL')
                  AND saved.report_payload #>> '{membership,basis}' = ?
                  AND (?::timestamptz AT TIME ZONE preparation.timezone)::date > preparation.period_end
                  AND (?::date IS NULL OR (preparation.period_start,preparation.id) > (?::date,?::uuid))
                  AND NOT EXISTS (
                      SELECT 1 FROM weekly_review_ai_jobs job
                      JOIN weekly_review_snapshots bound ON bound.id=job.snapshot_id
                      WHERE bound.store_id=store.id AND bound.period_start=preparation.period_start
                        AND bound.period_end=preparation.period_end AND bound.report_contract_version=3
                        AND (job.planning_origin <> 'AUTOMATIC'
                            OR (job.snapshot_id=saved.id AND NOT ((job.status='FAILED'
                                AND job.last_error_code='SNAPSHOT_NOT_CURRENT') IS TRUE))
                            OR job.attempt_count > 0 OR job.deadline_at <= ? OR job.deadline_at <= clock_timestamp()
                            OR EXISTS (SELECT 1 FROM weekly_review_ai_attempts WHERE job_id=job.id)
                            OR NOT ((job.status IN ('PENDING','RETRY_WAIT')
                                OR (job.status='RUNNING' AND job.lease_until <= ?
                                    AND job.lease_until <= clock_timestamp())
                                OR (job.status='FAILED' AND job.last_error_code='SNAPSHOT_NOT_CURRENT')) IS TRUE)))
                  AND NOT EXISTS (
                      SELECT 1 FROM weekly_review_ai_enrichments enrichment
                      JOIN weekly_review_snapshots bound ON bound.id=enrichment.snapshot_id
                      WHERE bound.store_id=store.id AND bound.period_start=preparation.period_start
                        AND bound.period_end=preparation.period_end AND bound.report_contract_version=3)
                ORDER BY preparation.period_start,preparation.id LIMIT ?
                """, (row, index) -> new Candidate(row.getObject("id", UUID.class),
                row.getObject("store_id", UUID.class), row.getObject("period_start", LocalDate.class),
                row.getObject("snapshot_id", UUID.class)), SellerWeeklyHistoricalMembership.BASIS, Timestamp.from(now),
                cursor == null ? null : cursor.periodStart(), cursor == null ? null : cursor.periodStart(),
                cursor == null ? null : cursor.preparationId(), Timestamp.from(now), Timestamp.from(now), maximum);
    }

    public record Candidate(UUID preparationId, UUID storeId, LocalDate periodStart, UUID snapshotId) { }
}
