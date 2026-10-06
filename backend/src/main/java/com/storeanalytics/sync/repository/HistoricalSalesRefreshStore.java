package com.storeanalytics.sync.repository;

import com.storeanalytics.sync.exception.HistoricalSalesDependencyException.LinkedReturn;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class HistoricalSalesRefreshStore {
    private final JdbcTemplate jdbc;

    public HistoricalSalesRefreshStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void initialize(UUID connection, Instant start, Instant end, int minutes, LocalDate day, Instant now) {
        jdbc.update("""
                INSERT INTO historical_sales_refresh_state
                (connection_id, history_start, cursor_start, cycle_end,
                 preferred_window_minutes, budget_day, cycle_started_at)
                VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (connection_id) DO NOTHING
                """, connection, Timestamp.from(start), Timestamp.from(start), Timestamp.from(end), minutes, day,
                Timestamp.from(now));
    }

    public Optional<State> find(UUID connection, boolean lock) {
        return jdbc.query("SELECT * FROM historical_sales_refresh_state WHERE connection_id = ?"
                + (lock ? " FOR UPDATE" : ""), (row, index) -> new State(
                        connection, row.getTimestamp("history_start").toInstant(),
                        row.getTimestamp("cursor_start").toInstant(), row.getTimestamp("cycle_end").toInstant(),
                        row.getObject("active_job_id", UUID.class), row.getInt("preferred_window_minutes"),
                        nullable(row.getTimestamp("last_success_at")),
                        nullable(row.getTimestamp("last_cycle_completed_at")),
                        nullable(row.getTimestamp("next_cycle_at")), row.getObject("budget_day", LocalDate.class),
                        row.getInt("request_attempts"), row.getTimestamp("cycle_started_at").toInstant(),
                        nullable(row.getTimestamp("blocked_at")), nullable(row.getTimestamp("blocked_start")),
                        nullable(row.getTimestamp("blocked_end")), row.getString("block_reason"),
                        nullable(row.getTimestamp("repair_start")), nullable(row.getTimestamp("repair_end")),
                        row.getBoolean("backfill_repair_allowed"), row.getBoolean("repair_dependencies_required"),
                        linkedReturns(row)),
                connection).stream().findFirst();
    }

    public void attach(UUID connection, UUID job) {
        jdbc.update("UPDATE historical_sales_refresh_state SET active_job_id = ? WHERE connection_id = ?",
                job, connection);
    }

    public void release(UUID connection, int minutes) {
        jdbc.update("""
                UPDATE historical_sales_refresh_state SET active_job_id = NULL, preferred_window_minutes = ?
                WHERE connection_id = ?
                """, minutes, connection);
    }

    public void beginCycle(UUID connection, Instant end, Instant now) {
        jdbc.update("""
                UPDATE historical_sales_refresh_state SET cursor_start = history_start,
                    cycle_end = ?, next_cycle_at = NULL, cycle_started_at = ? WHERE connection_id = ?
                """, Timestamp.from(end), Timestamp.from(now), connection);
    }

    public void advance(State state, Instant end, Instant now, Instant nextCycle, int minutes) {
        boolean completed = end.equals(state.cycleEnd());
        jdbc.update("""
                UPDATE historical_sales_refresh_state SET cursor_start = ?, active_job_id = NULL,
                    preferred_window_minutes = ?, last_success_at = ?,
                    last_cycle_completed_at = CASE WHEN ? THEN ? ELSE last_cycle_completed_at END,
                    next_cycle_at = CASE WHEN ? THEN CAST(? AS timestamptz) ELSE NULL END,
                    blocked_at = NULL, blocked_start = NULL, blocked_end = NULL, block_reason = NULL,
                    repair_start = NULL, repair_end = NULL, backfill_repair_allowed = true,
                    repair_dependencies_required = false, repair_return_ids = '{}', repair_parent_ids = '{}'
                WHERE connection_id = ?
                """, Timestamp.from(end), minutes, Timestamp.from(now), completed, Timestamp.from(now),
                completed, Timestamp.from(nextCycle), state.connectionId());
    }

    public void charge(UUID connection, LocalDate day) {
        jdbc.update("""
                UPDATE historical_sales_refresh_state SET request_attempts =
                    CASE WHEN budget_day = ? THEN request_attempts + 1 ELSE 1 END, budget_day = ?
                WHERE connection_id = ?
                """, day, day, connection);
    }

    public void block(UUID connection, Instant start, Instant end, Instant at) {
        jdbc.update("""
                UPDATE historical_sales_refresh_state SET blocked_start = ?, blocked_end = ?,
                    blocked_at = ?, block_reason = 'PERMANENT_FAILURE', repair_start = ?, repair_end = ?,
                    backfill_repair_allowed = true, repair_dependencies_required = false,
                    repair_return_ids = '{}', repair_parent_ids = '{}'
                WHERE connection_id = ?
                """, Timestamp.from(start), Timestamp.from(end), Timestamp.from(at),
                Timestamp.from(start), Timestamp.from(end), connection);
    }

    public void repairRange(UUID connection, Instant start, Instant end, boolean allowed, List<LinkedReturn> targets) {
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("Historical dependency target set is empty");
        }
        jdbc.update("""
                UPDATE historical_sales_refresh_state SET repair_start = ?, repair_end = ?, backfill_repair_allowed = ?,
                    repair_dependencies_required = true, repair_return_ids = ?::uuid[], repair_parent_ids = ?::uuid[]
                WHERE connection_id = ? AND blocked_at IS NOT NULL
                """, Timestamp.from(start), Timestamp.from(end), allowed,
                uuidArray(targets.stream().map(LinkedReturn::returnId).toList()),
                uuidArray(targets.stream().map(LinkedReturn::parentId).toList()), connection);
    }

    private static String uuidArray(List<UUID> ids) {
        return "{" + String.join(",", ids.stream().map(UUID::toString).toList()) + "}";
    }

    private static List<LinkedReturn> linkedReturns(ResultSet row) throws SQLException {
        Object[] returns = (Object[]) row.getArray("repair_return_ids").getArray();
        Object[] parents = (Object[]) row.getArray("repair_parent_ids").getArray();
        List<LinkedReturn> result = new ArrayList<>();
        for (int i = 0; i < returns.length; i++) {
            result.add(new LinkedReturn(UUID.fromString(returns[i].toString()),
                    UUID.fromString(parents[i].toString())));
        }
        return List.copyOf(result);
    }

    public void classificationBlocked(UUID connection, boolean blocked) {
        jdbc.update("""
                UPDATE historical_sales_refresh_state SET block_reason = ?
                WHERE connection_id = ? AND blocked_at IS NULL
                """, blocked ? "CLASSIFICATION_REQUIRED" : null, connection);
    }

    private static Instant nullable(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record State(UUID connectionId, Instant historyStart, Instant cursorStart, Instant cycleEnd,
                        UUID activeJobId, int preferredWindowMinutes, Instant lastSuccessAt,
                        Instant lastCycleCompletedAt, Instant nextCycleAt, LocalDate budgetDay,
                        int requestAttempts, Instant cycleStartedAt, Instant blockedAt, Instant blockedStart,
                        Instant blockedEnd, String blockReason, Instant repairStart, Instant repairEnd,
                        boolean backfillRepairAllowed, boolean repairDependenciesRequired,
                        List<LinkedReturn> linkedReturns) {
    }
}
