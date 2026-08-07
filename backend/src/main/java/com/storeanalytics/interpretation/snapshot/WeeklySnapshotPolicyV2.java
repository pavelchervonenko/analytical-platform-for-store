package com.storeanalytics.interpretation.snapshot;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Versions;

/**
 * Active weekly quality policy. V2 adds an explicit completed-sales sample to employee facts and
 * applies its sufficiency to category/structure facts without rewriting immutable V1 snapshots.
 */
public final class WeeklySnapshotPolicyV2 extends WeeklySnapshotPolicyV1 {

    public static final Versions VERSIONS = new Versions(
            1,
            "weekly-metrics-v2",
            "weekly-snapshot-v5",
            "weekly-quality-v2"
    );
}
