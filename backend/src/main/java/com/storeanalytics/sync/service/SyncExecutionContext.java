package com.storeanalytics.sync.service;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.sync.model.SyncTriggerType;
import java.util.UUID;

public record SyncExecutionContext(
        SyncTriggerType triggerType,
        UUID syncJobId,
        AppUser requestedBy
) {
    public SyncExecutionContext {
        java.util.Objects.requireNonNull(triggerType, "triggerType");
        if ((triggerType == SyncTriggerType.MANUAL) != (syncJobId == null)) {
            throw new IllegalArgumentException(
                    "manual executions must not belong to a sync job"
            );
        }
    }

    public static SyncExecutionContext manual() {
        return new SyncExecutionContext(SyncTriggerType.MANUAL, null, null);
    }
}
