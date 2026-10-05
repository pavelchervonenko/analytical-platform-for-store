package com.storeanalytics.interpretation.review;

import com.storeanalytics.interpretation.review.SellerWeeklyPreparationStore.Claim;
import com.storeanalytics.interpretation.review.SellerWeeklyPreparationStore.Deferral;
import com.storeanalytics.metrics.service.SellerHistoricalFactsUnavailableException;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** One free historical preparation per invocation; deliberately has no scheduler or AI dependency. */
@Component
class SellerWeeklyPreparationRunner {
    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final Duration BACKOFF = Duration.ofMinutes(15);
    private static final Set<String> HISTORY_WAITS = Set.of("MEMBERSHIP_BASELINE_MISSING",
            "MEMBERSHIP_BASELINE_DOES_NOT_COVER_COMPARISON", "HISTORY_BASELINE_UNAVAILABLE",
            "DOCUMENT_MEMBERSHIP_OR_AUTHOR_UNKNOWN", "ATTACH_MEMBERSHIP_OR_AUTHOR_UNKNOWN");
    private static final Set<String> SOURCE_WAITS = Set.of("SOURCE_COVERAGE_INCOMPLETE",
            "SOURCE_WRITES_ACTIVE", "SOURCE_RECONCILIATION_REQUIRED", "WEEK_NOT_CLOSED", "PERIOD_NOT_CLOSED");
    private final SellerWeeklyPreparationStore queue;
    private final SellerWeeklyHistoricalFactsSource facts;
    private final WeeklyReviewSnapshotStore snapshots;
    private final Clock clock;

    SellerWeeklyPreparationRunner(SellerWeeklyPreparationStore queue, SellerWeeklyHistoricalFactsSource facts,
            WeeklyReviewSnapshotStore snapshots, Clock clock) {
        this.queue = queue;
        this.facts = facts;
        this.snapshots = snapshots;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Result prepareNext(String owner) {
        var claimed = queue.claimNext(owner, LEASE, clock.instant());
        if (claimed.isEmpty()) {
            return new Result(null, "IDLE", null);
        }
        Claim claim = claimed.get();
        try {
            var selected = facts.load(claim.storeId(), claim.periodStart(), claim.timezone(), clock.instant());
            if (!queue.heartbeat(claim, LEASE, clock.instant())) {
                return new Result(claim.id(), "LEASE_LOST", null);
            }
            var snapshot = snapshots.persistHistoricalCandidate(selected, clock.instant());
            if (queue.completeWithSnapshot(claim, snapshot.id(), clock.instant())) {
                return new Result(claim.id(), "SUCCEEDED", snapshot.id());
            }
            return defer(claim, Deferral.WAITING_SOURCES, "SOURCE_OR_LEASE_CHANGED");
        } catch (SellerWeeklySourceChangedException changed) {
            return defer(claim, Deferral.WAITING_SOURCES, "SOURCE_CHANGED");
        } catch (SellerHistoricalFactsUnavailableException unavailable) {
            String reason = unavailable.getMessage();
            if (reason != null && HISTORY_WAITS.contains(reason)) {
                return defer(claim, Deferral.WAITING_HISTORY, reason);
            }
            if (reason != null && SOURCE_WAITS.contains(reason)) {
                return defer(claim, Deferral.WAITING_SOURCES, reason);
            }
            return defer(claim, Deferral.FAILED, "HISTORICAL_CONTRACT_REJECTED");
        } catch (RuntimeException failure) {
            // Never persist/log exception messages: JDBC/provider context may contain sensitive fields.
            return defer(claim, Deferral.FAILED, "PREPARATION_EXECUTION_FAILED");
        }
    }

    private Result defer(Claim claim, Deferral state, String reason) {
        boolean owned = queue.defer(claim, state, reason, BACKOFF, clock.instant());
        return new Result(claim.id(), owned ? state.name() : "LEASE_LOST", null);
    }

    record Result(UUID jobId, String state, UUID snapshotId) { }
}
