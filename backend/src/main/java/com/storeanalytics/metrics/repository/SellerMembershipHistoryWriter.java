package com.storeanalytics.metrics.repository;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Writes only forward-moving membership intervals under the store publication lock. */
@Repository
public class SellerMembershipHistoryWriter {

    public enum ChangeSource {
        SYNC,
        MANUAL
    }

    private final JdbcTemplate jdbc;

    public SellerMembershipHistoryWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lockStore(UUID storeId) {
        requireWritableTransaction();
        UUID selected = requireNonNull(storeId, "storeId");
        List<UUID> locked = jdbc.query("SELECT id FROM stores WHERE id = ? FOR UPDATE",
                (row, index) -> row.getObject("id", UUID.class), selected);
        if (locked.size() != 1) {
            throw new IllegalArgumentException("Store is unavailable for membership publication");
        }
    }

    /** A post-baseline assignment without an open interval is not yet a published observation. */
    public boolean manualChangeAllowed(UUID storeId, UUID employeeId) {
        requireWritableTransaction();
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT NOT EXISTS (
                    SELECT 1 FROM store_seller_membership_state WHERE store_id = ?
                ) OR EXISTS (
                    SELECT 1 FROM seller_membership_history
                    WHERE store_id = ? AND employee_id = ? AND valid_to IS NULL
                )
                """, Boolean.class, requireNonNull(storeId, "storeId"), storeId,
                requireNonNull(employeeId, "employeeId")));
    }

    /** Must follow a reviewed source/roster check; the migration never calls this automatically. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean bootstrapStore(UUID storeId, String baselineSource) {
        String source = requireText(baselineSource, "baselineSource");
        if (source.length() > 120) {
            throw new IllegalArgumentException("baselineSource exceeds 120 characters");
        }
        UUID store = requireNonNull(storeId, "storeId");
        lockStore(store);
        Integer existing = jdbc.queryForObject("""
                SELECT count(*) FROM store_seller_membership_state WHERE store_id = ?
                """, Integer.class, store);
        if (existing != null && existing > 0) {
            return false;
        }
        Instant boundary = databaseNow();
        jdbc.update("""
                INSERT INTO store_seller_membership_state
                    (store_id, authoritative_from, baseline_source, baseline_recorded_at)
                VALUES (?, ?, ?, ?)
                """, store, Timestamp.from(boundary), source, Timestamp.from(boundary));
        jdbc.update("""
                INSERT INTO seller_membership_history
                    (employee_id, store_id, employee_active, assignment_active,
                     participates_in_ranking, valid_from, change_source, effective_time_source)
                SELECT assignment.employee_id, assignment.store_id, employee.is_active,
                       assignment.is_active, assignment.participates_in_ranking,
                       ?, 'BASELINE', 'APPROVED_BASELINE'
                FROM employee_store_assignments assignment
                JOIN employees employee ON employee.id = assignment.employee_id
                WHERE assignment.store_id = ?
                """, Timestamp.from(boundary), store);
        return true;
    }

    /** Call after all current employee/assignment rows have been changed in the same transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean reconcileStore(UUID storeId, ChangeSource source, UUID actorId, UUID syncRunId) {
        requireWritableTransaction();
        UUID store = requireNonNull(storeId, "storeId");
        ChangeSource changeSource = requireNonNull(source, "source");
        if ((changeSource == ChangeSource.MANUAL && actorId == null)
                || (changeSource == ChangeSource.SYNC && syncRunId == null)) {
            throw new IllegalArgumentException("Membership change provenance is required");
        }
        lockStore(store);
        List<Instant> baseline = jdbc.query("""
                SELECT authoritative_from FROM store_seller_membership_state WHERE store_id = ?
                """, (row, index) -> row.getTimestamp("authoritative_from").toInstant(), store);
        if (baseline.isEmpty()) {
            return false;
        }
        Map<UUID, MemberState> current = new HashMap<>();
        jdbc.query("""
                SELECT assignment.employee_id, employee.is_active AS employee_active,
                       assignment.is_active AS assignment_active,
                       assignment.participates_in_ranking
                FROM employee_store_assignments assignment
                JOIN employees employee ON employee.id = assignment.employee_id
                WHERE assignment.store_id = ?
                """, row -> {
                    current.put(row.getObject("employee_id", UUID.class),
                            new MemberState(row.getBoolean("employee_active"),
                                    row.getBoolean("assignment_active"),
                                    row.getBoolean("participates_in_ranking")));
                }, store);
        Map<UUID, OpenInterval> open = new HashMap<>();
        jdbc.query("""
                SELECT history.id, history.employee_id, history.valid_from,
                       history.employee_active, history.assignment_active,
                       history.participates_in_ranking, employee.is_active AS employee_active_now
                FROM seller_membership_history history
                JOIN employees employee ON employee.id = history.employee_id
                WHERE history.store_id = ? AND history.valid_to IS NULL
                """, row -> {
                    open.put(row.getObject("employee_id", UUID.class),
                            new OpenInterval(row.getObject("id", UUID.class),
                                    row.getTimestamp("valid_from").toInstant(),
                                    new MemberState(row.getBoolean("employee_active"),
                                            row.getBoolean("assignment_active"),
                                            row.getBoolean("participates_in_ranking")),
                                    row.getBoolean("employee_active_now")));
                }, store);
        Set<UUID> employees = new HashSet<>(current.keySet());
        employees.addAll(open.keySet());
        Instant observed = databaseNow();
        boolean changed = false;
        for (UUID employeeId : employees.stream().sorted().toList()) {
            OpenInterval previous = open.get(employeeId);
            MemberState next = current.get(employeeId);
            if (next == null) {
                next = new MemberState(previous.employeeActiveNow(), false,
                        previous.state().participatesInRanking());
            }
            if (previous != null && previous.state().equals(next)) {
                continue;
            }
            Instant effective = previous == null ? observed
                    : observed.isAfter(previous.validFrom()) ? observed
                    : previous.validFrom().plus(1, ChronoUnit.MICROS);
            if (previous != null) {
                jdbc.update("""
                        UPDATE seller_membership_history SET valid_to = ?
                        WHERE id = ? AND valid_to IS NULL
                        """, Timestamp.from(effective), previous.id());
            }
            jdbc.update("""
                    INSERT INTO seller_membership_history
                        (employee_id, store_id, employee_active, assignment_active,
                         participates_in_ranking, valid_from, change_source,
                         effective_time_source, actor_id, sync_run_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'OBSERVED', ?, ?)
                    """, employeeId, store, next.employeeActive(), next.assignmentActive(),
                    next.participatesInRanking(), Timestamp.from(effective),
                    changeSource.name(), actorId, syncRunId);
            changed = true;
        }
        if (changed) {
            jdbc.update("""
                    UPDATE store_seller_membership_state
                    SET membership_revision = membership_revision + 1,
                        updated_at = clock_timestamp()
                    WHERE store_id = ?
                    """, store);
        }
        return changed;
    }

    private Instant databaseNow() {
        return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
    }

    private void requireWritableTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Membership publication requires a writable transaction");
        }
    }

    private record MemberState(boolean employeeActive, boolean assignmentActive,
                               boolean participatesInRanking) {
    }

    private record OpenInterval(UUID id, Instant validFrom, MemberState state,
                                boolean employeeActiveNow) {
    }
}
