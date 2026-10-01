package com.storeanalytics.sync.service;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.sync.model.SyncTriggerType;
import java.util.UUID;

public record SyncExecutionContext(
        SyncTriggerType triggerType,
        UUID syncJobId,
        AppUser requestedBy,
        Integer jobAttempt
) {
    public SyncExecutionContext(SyncTriggerType triggerType, UUID syncJobId, AppUser requestedBy) {
        this(triggerType, syncJobId, requestedBy, null);
    }

    public SyncExecutionContext {
        java.util.Objects.requireNonNull(triggerType, "triggerType");
        if ((triggerType == SyncTriggerType.MANUAL) != (syncJobId == null)) {
            throw new IllegalArgumentException(
                    "manual executions must not belong to a sync job"
            );
        }
        if (jobAttempt != null && jobAttempt < 1) {
            throw new IllegalArgumentException("jobAttempt must be positive");
        }
    }

    public static SyncExecutionContext manual() {
        return new SyncExecutionContext(SyncTriggerType.MANUAL, null, null);
    }
}
