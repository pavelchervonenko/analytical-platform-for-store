package com.storeanalytics.interpretation.snapshot;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import java.util.UUID;

public record SnapshotEmployeeMembership(
        UUID employeeId,
        String employeeRef,
        String displayNameSnapshot
) {

    public SnapshotEmployeeMembership {
        requireNonNull(employeeId, "employeeId");
        requireText(employeeRef, "employeeRef");
        requireText(displayNameSnapshot, "displayNameSnapshot");
    }
}
