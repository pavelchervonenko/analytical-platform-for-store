package com.storeanalytics.interpretation.snapshot;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.QualityStatus;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Versions;
import java.util.List;
import java.util.UUID;

public record WeeklySnapshotDraft(
        UUID storeId,
        WeeklyAnalyticsFactsQuery query,
        String timezone,
        QualityStatus qualityStatus,
        Versions versions,
        List<SnapshotEmployeeMembership> employees,
        WeeklySnapshotPayload payload,
        String factsHash
) {

    public WeeklySnapshotDraft {
        requireNonNull(storeId, "storeId");
        requireNonNull(query, "query");
        requireText(timezone, "timezone");
        requireNonNull(qualityStatus, "qualityStatus");
        requireNonNull(versions, "versions");
        employees = List.copyOf(requireNonNull(employees, "employees"));
        requireNonNull(payload, "payload");
        requireText(factsHash, "factsHash");
    }
}
