package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import java.util.UUID;

/** Bounded counters and a cursor only: never retains facts, snapshots or business values. */
record SellerWeeklyV3BatchPlanningResult(
        int scanned, int evaluated, int unchanged, int deferred, UUID afterStoreId, StopReason stopReason
) {
    enum StopReason {
        EXHAUSTED,
        STORE_LIMIT,
        TIME_BUDGET
    }

    SellerWeeklyV3BatchPlanningResult {
        require(scanned >= 0 && evaluated >= 0 && unchanged >= 0 && deferred >= 0,
                "Batch counters must be non-negative");
        require((long) evaluated + unchanged + deferred == scanned, "Batch counters must reconcile");
        require(scanned == 0 || afterStoreId != null, "A processed batch requires a continuation cursor");
        requireNonNull(stopReason, "stopReason");
    }
}
