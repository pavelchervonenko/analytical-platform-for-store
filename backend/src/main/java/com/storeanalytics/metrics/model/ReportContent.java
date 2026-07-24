package com.storeanalytics.metrics.model;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireJson;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.auth.model.AppUser;
import java.time.Instant;

public record ReportContent(
        ReportIntegrity integrity,
        String payload,
        Instant generatedAt,
        AppUser generatedBy,
        Instant approvedAt,
        AppUser approvedBy
) {

    public ReportContent {
        integrity = requireNonNull(integrity, "integrity");
        payload = requireJson(payload, "payload");
        generatedAt = requireNonNull(generatedAt, "generatedAt");
    }

    public void validateStatus(ReportStatus status) {
        require((status != ReportStatus.APPROVED && status != ReportStatus.ARCHIVED
                        && status != ReportStatus.FINALIZED)
                        || approvedAt != null,
                "approvedAt is required for finalized reports");
    }
}
