package com.storeanalytics.interpretation.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WeeklySnapshotJobFailureClassifierTest {

    @Test
    void classifiesCancellationWithoutRetryOrSensitiveDetails() {
        WeeklySnapshotJobFailure failure = new WeeklySnapshotJobFailureClassifier()
                .classify(new WeeklySnapshotJobCancellationException());

        assertThat(failure.retryable()).isFalse();
        assertThat(failure.errorCode()).isEqualTo("SNAPSHOT_CANCELLED");
        assertThat(failure.safeSummary())
                .isEqualTo("Weekly snapshot execution failed: SNAPSHOT_CANCELLED");
    }
}
