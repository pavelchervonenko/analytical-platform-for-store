package com.storeanalytics.interpretation.snapshot;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Facts;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Manifest;

/** Canonical body persisted in analytics_snapshots.facts_payload. */
public record WeeklySnapshotPayload(
        int contractVersion,
        Manifest manifest,
        Facts facts
) {

    public WeeklySnapshotPayload {
        require(contractVersion == 1, "contractVersion must be 1");
        requireNonNull(manifest, "manifest");
        requireNonNull(facts, "facts");
    }
}
