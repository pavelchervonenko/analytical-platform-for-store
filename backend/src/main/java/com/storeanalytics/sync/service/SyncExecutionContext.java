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
        // This is the persisted failure counter, not a one-based attempt number.
        // First execution and every successfully advanced phase are fenced by zero.
        if (jobAttempt != null && jobAttempt < 0) {
            throw new IllegalArgumentException("jobAttempt must not be negative");
        }
    }

    public static SyncExecutionContext manual() {
        return new SyncExecutionContext(SyncTriggerType.MANUAL, null, null);
    }
}
