package com.storeanalytics.audit.model;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import java.time.Instant;

public record AuditRetention(
        AuditRetentionClass retentionClass,
        Instant retainUntil
) {

    public AuditRetention {
        requireNonNull(retentionClass, "retentionClass");
        require(
                (retentionClass == AuditRetentionClass.FINANCIAL)
                        == (retainUntil == null),
                "only financial audit entries may have no retention deadline"
        );
    }
}
