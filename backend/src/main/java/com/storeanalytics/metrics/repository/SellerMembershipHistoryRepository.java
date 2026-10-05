package com.storeanalytics.metrics.repository;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Historical eligibility is unknown unless an approved baseline and covering interval exist. */
@Repository
public class SellerMembershipHistoryRepository {

    public enum Eligibility {
        ELIGIBLE,
        NOT_ELIGIBLE,
        UNKNOWN
    }

    private final JdbcTemplate jdbc;

    public SellerMembershipHistoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Eligibility eligibilityAt(UUID storeId, UUID employeeId, Instant occurredAt) {
        UUID store = requireNonNull(storeId, "storeId");
        UUID employee = requireNonNull(employeeId, "employeeId");
        Timestamp moment = Timestamp.from(requireNonNull(occurredAt, "occurredAt"));
        List<Eligibility> found = jdbc.query("""
                SELECT CASE
                    WHEN ?::timestamptz < state.authoritative_from OR history.id IS NULL
                        THEN 'UNKNOWN'
                    WHEN history.employee_active AND history.assignment_active
                         AND history.participates_in_ranking THEN 'ELIGIBLE'
                    ELSE 'NOT_ELIGIBLE'
                END AS eligibility
                FROM store_seller_membership_state state
                LEFT JOIN LATERAL (
                    SELECT id, employee_active, assignment_active, participates_in_ranking
                    FROM seller_membership_history
                    WHERE store_id = state.store_id AND employee_id = ?
                      AND valid_from <= ?::timestamptz
                      AND (valid_to IS NULL OR valid_to > ?::timestamptz)
                ) history ON true
                WHERE state.store_id = ?
                """, (row, index) -> Eligibility.valueOf(row.getString("eligibility")),
                moment, employee, moment, moment, store);
        return found.isEmpty() ? Eligibility.UNKNOWN : found.getFirst();
    }

    public Optional<Instant> authoritativeFrom(UUID storeId) {
        return jdbc.query("SELECT authoritative_from FROM store_seller_membership_state WHERE store_id = ?",
                (row, index) -> row.getTimestamp("authoritative_from").toInstant(),
                requireNonNull(storeId, "storeId")).stream().findFirst();
    }

    /** Every historically eligible member, including departed members without current assignments. */
    public List<UUID> eligibleDuring(UUID storeId, Instant start, Instant endExclusive) {
        requireNonNull(start, "start");
        requireNonNull(endExclusive, "endExclusive");
        if (!start.isBefore(endExclusive)) {
            throw new IllegalArgumentException("Historical interval must be nonempty");
        }
        return jdbc.query("""
                SELECT DISTINCT employee_id FROM seller_membership_history
                WHERE store_id = ? AND employee_active AND assignment_active AND participates_in_ranking
                  AND valid_from < ? AND (valid_to IS NULL OR valid_to > ?)
                ORDER BY employee_id
                """, (row, index) -> row.getObject("employee_id", UUID.class),
                requireNonNull(storeId, "storeId"), Timestamp.from(endExclusive), Timestamp.from(start));
    }
}
