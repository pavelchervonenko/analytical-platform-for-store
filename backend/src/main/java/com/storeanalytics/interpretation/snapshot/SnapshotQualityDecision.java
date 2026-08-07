package com.storeanalytics.interpretation.snapshot;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.EvidenceIndexEntry;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Limitation;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.QualityStatus;
import java.util.List;

public record SnapshotQualityDecision(
        QualityStatus status,
        List<EvidenceIndexEntry> unavailableEvidence,
        List<Limitation> limitations
) {

    public SnapshotQualityDecision {
        requireNonNull(status, "status");
        unavailableEvidence = List.copyOf(requireNonNull(
                unavailableEvidence,
                "unavailableEvidence"
        ));
        limitations = List.copyOf(requireNonNull(limitations, "limitations"));
    }
}
