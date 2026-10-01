package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.interpretation.review.SellerWeeklyV3BatchPlanningResult.StopReason;
import com.storeanalytics.interpretation.snapshot.WeeklySnapshotPlanningStore;
import java.time.Duration;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Internal and unscheduled. One page of targets and one store's facts at a time. */
@Service
class SellerWeeklyV3BatchPlanningService {

    private final WeeklySnapshotPlanningStore stores;
    private final SellerWeeklyV3PlanningService planner;
    private final LongSupplier nanoTime;

    @Autowired
    SellerWeeklyV3BatchPlanningService(WeeklySnapshotPlanningStore stores, SellerWeeklyV3PlanningService planner) {
        this(stores, planner, System::nanoTime);
    }

    SellerWeeklyV3BatchPlanningService(WeeklySnapshotPlanningStore stores, SellerWeeklyV3PlanningService planner,
                                       LongSupplier nanoTime) {
        this.stores = requireNonNull(stores, "stores");
        this.planner = requireNonNull(planner, "planner");
        this.nanoTime = requireNonNull(nanoTime, "nanoTime");
    }

    // Do not combine RR assessments and fenced RC writes in an outer transaction.
    @Transactional(propagation = Propagation.NEVER)
    SellerWeeklyV3BatchPlanningResult scan(UUID afterStoreId, Budget budget) {
        Budget selectedBudget = requireNonNull(budget, "budget");
        long started = nanoTime.getAsLong();
        long timeBudget = selectedBudget.timeBudget().toNanos();
        UUID cursor = afterStoreId;
        int scanned = 0;
        int evaluated = 0;
        int unchanged = 0;
        int deferred = 0;
        while (scanned < selectedBudget.maxStores()) {
            if (nanoTime.getAsLong() - started >= timeBudget) {
                return result(scanned, evaluated, unchanged, deferred, cursor, StopReason.TIME_BUDGET);
            }
            int limit = Math.min(selectedBudget.pageSize(), selectedBudget.maxStores() - scanned);
            var page = stores.activeStoresAfter(cursor, limit);
            if (page.isEmpty()) {
                return result(scanned, evaluated, unchanged, deferred, cursor, StopReason.EXHAUSTED);
            }
            for (var store : page) {
                if (nanoTime.getAsLong() - started >= timeBudget) {
                    return result(scanned, evaluated, unchanged, deferred, cursor, StopReason.TIME_BUDGET);
                }
                // Infrastructure and invalid configuration errors propagate, never become DEFERRED.
                switch (planner.evaluate(store.storeId()).outcome()) {
                    case EVALUATED -> evaluated++;
                    case UNCHANGED -> unchanged++;
                    case DEFERRED -> deferred++;
                    default -> throw new IllegalStateException("Unsupported seller planning outcome");
                }
                scanned++;
                cursor = store.storeId();
            }
            // Check before the next query as well: a short page does not pin the target set forever.
        }
        // No extra query to distinguish exhaustion from an exact cap; resume can return EXHAUSTED.
        return result(scanned, evaluated, unchanged, deferred, cursor, StopReason.STORE_LIMIT);
    }

    private SellerWeeklyV3BatchPlanningResult result(int scanned, int evaluated, int unchanged, int deferred,
                                                     UUID cursor, StopReason reason) {
        return new SellerWeeklyV3BatchPlanningResult(scanned, evaluated, unchanged, deferred, cursor, reason);
    }

    /** Cooperative deadline, checked between stores; never cancels a store's in-flight transaction. */
    record Budget(int maxStores, int pageSize, Duration timeBudget) {
        Budget {
            require(maxStores >= 1 && maxStores <= 100, "maxStores must be between 1 and 100");
            require(pageSize >= 1 && pageSize <= 25, "pageSize must be between 1 and 25");
            requireNonNull(timeBudget, "timeBudget");
            require(timeBudget.compareTo(Duration.ofMillis(1)) >= 0
                    && timeBudget.compareTo(Duration.ofMinutes(5)) <= 0,
                    "timeBudget must be between 1 millisecond and 5 minutes");
        }
    }
}
