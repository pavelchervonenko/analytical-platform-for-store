package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import java.util.Optional;

/** Internal planning result: content can remain available while its source is stale. */
record SellerWeeklyV3ReadResult(
        State state,
        Optional<PersistedWeeklyReviewV3Snapshot> snapshot
) {
    enum State {
        PREPARING,
        CURRENT,
        STALE
    }

    SellerWeeklyV3ReadResult {
        requireNonNull(state, "state");
        requireNonNull(snapshot, "snapshot");
        if ((state == State.CURRENT || state == State.STALE) && snapshot.isEmpty()) {
            throw new IllegalArgumentException("Current or stale v3 state requires a snapshot");
        }
        if (state == State.PREPARING && snapshot.isPresent()) {
            throw new IllegalArgumentException("Preparing v3 state cannot expose a snapshot");
        }
    }
}
