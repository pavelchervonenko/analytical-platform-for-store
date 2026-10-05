package com.storeanalytics.interpretation.review;

import com.storeanalytics.metrics.service.SellerHistoricalComparisonFacts;
import com.storeanalytics.metrics.service.SellerHistoricalFactsUnavailableException;
import com.storeanalytics.metrics.service.SellerCohortSnapshot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** No names, money or provider identifiers enter historical source identity. */
@Component
class SellerWeeklyHistoricalIdentity {
    private final JdbcTemplate jdbc;

    SellerWeeklyHistoricalIdentity(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    SellerWeeklyHistoricalMembership read(UUID storeId, ClosedSellerWeek week,
            SellerHistoricalComparisonFacts facts) {
        return read(storeId, week, facts.comparison().current().metrics().cohort(), facts.actionEmployeeIds());
    }

    SellerWeeklyHistoricalMembership read(UUID storeId, ClosedSellerWeek week,
            SellerCohortSnapshot cohort, Set<UUID> actionIds) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !Integer.valueOf(java.sql.Connection.TRANSACTION_REPEATABLE_READ)
                        .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
            throw new IllegalStateException("Historical identity requires the facts repeatable-read transaction");
        }
        List<Baseline> baselines = jdbc.query("""
                SELECT authoritative_from, membership_revision
                FROM store_seller_membership_state WHERE store_id = ?
                """, (row, index) -> new Baseline(row.getTimestamp("authoritative_from").toInstant(),
                        row.getLong("membership_revision")), storeId);
        if (baselines.isEmpty() || !week.hasAuthoritativeComparison(baselines.getFirst().from())) {
            throw new SellerHistoricalFactsUnavailableException("HISTORY_BASELINE_UNAVAILABLE");
        }
        Baseline baseline = baselines.getFirst();
        Instant start = week.previous().startInstant();
        Instant end = week.closesAt();
        List<Interval> intervals = jdbc.query("""
                SELECT employee_id, greatest(valid_from, ?) AS clipped_start,
                       least(coalesce(valid_to, ?), ?) AS clipped_end,
                       employee_active, assignment_active, participates_in_ranking
                FROM seller_membership_history
                WHERE store_id = ? AND valid_from < ? AND (valid_to IS NULL OR valid_to > ?)
                ORDER BY employee_id, valid_from
                """, (row, index) -> new Interval(row.getObject("employee_id", UUID.class),
                        row.getTimestamp("clipped_start").toInstant(), row.getTimestamp("clipped_end").toInstant(),
                        row.getBoolean("employee_active"), row.getBoolean("assignment_active"),
                        row.getBoolean("participates_in_ranking")), Timestamp.from(start), Timestamp.from(end),
                Timestamp.from(end), storeId, Timestamp.from(end), Timestamp.from(start));
        List<UUID> eligible = intervals.stream().filter(Interval::eligible).map(Interval::employee)
                .distinct().sorted().toList();
        if (!storeId.equals(cohort.storeId()) || !eligible.equals(cohort.employeeIds())
                || !eligible.containsAll(actionIds)) {
            throw new SellerHistoricalFactsUnavailableException("HISTORICAL_COHORT_CHANGED");
        }
        String selection = String.join("\n", SellerWeeklyHistoricalMembership.BASIS, storeId.toString(),
                week.zone().getId(), start.toString(), end.toString(), baseline.from().toString(),
                String.join("\n", intervals.stream().map(Interval::canonical).toList()));
        String actionable = String.join("\n", "seller-actionability-v1", storeId.toString(),
                String.join("\n", actionIds.stream().sorted().map(UUID::toString).toList()));
        return new SellerWeeklyHistoricalMembership(baseline.from(), baseline.revision(),
                hash(selection), hash(actionable));
    }

    static String sourceHash(SellerWeeklyHistoricalFacts facts) {
        return sourceHash(facts.storeId(), facts.period(), facts.sourceRevision(), facts.membership(),
                facts.historical().comparison().current().attachFormulaVersion(),
                facts.historical().comparison().previous().attachFormulaVersion());
    }

    static String sourceHash(SellerWeeklyHistoricalIdentityFacts facts) {
        return sourceHash(facts.storeId(), facts.period(), facts.sourceRevision(), facts.membership(),
                facts.attachFormulaVersion(), facts.attachFormulaVersion());
    }

    private static String sourceHash(UUID storeId, WeeklyReviewResponse.PeriodContext period, long revision,
            SellerWeeklyHistoricalMembership membership, String currentAttach, String previousAttach) {
        var versions = SellerWeeklyV3Assembler.historicalVersions();
        return hash(String.join("\n", "seller-weekly-historical-source-v1", storeId.toString(),
                period.timezone(), period.current().start().toString(),
                period.current().end().toString(), period.previous().start().toString(),
                period.previous().end().toString(), Long.toString(revision),
                membership.authoritativeFrom().toString(), Long.toString(membership.revision()),
                membership.selectionHash(), membership.actionabilityHash(),
                currentAttach, previousAttach, "STABLE", "SALES_RETURNS_ORDERS_BOTH_WEEKS_COMPLETE",
                versions.metricsPolicy(), versions.snapshotPolicy(), versions.qualityPolicy()));
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record Baseline(Instant from, long revision) { }

    private record Interval(UUID employee, Instant start, Instant end,
            boolean employeeActive, boolean assignmentActive, boolean participates) {
        boolean eligible() {
            return employeeActive && assignmentActive && participates;
        }

        String canonical() {
            return employee + "," + start + "," + end + "," + employeeActive + "," + assignmentActive
                    + "," + participates;
        }
    }
}
