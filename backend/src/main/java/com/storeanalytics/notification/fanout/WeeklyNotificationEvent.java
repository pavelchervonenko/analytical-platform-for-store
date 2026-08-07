package com.storeanalytics.notification.fanout;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record WeeklyNotificationEvent(
        UUID id,
        UUID storeId,
        String storeName,
        String eventType,
        UUID interpretationId,
        UUID snapshotId,
        int revision,
        int contentSchemaVersion,
        LocalDate periodStart,
        LocalDate periodEnd,
        String contentPayload,
        String contentHash,
        Instant notBefore,
        Instant expiresAt
) {

    public WeeklyNotificationEvent {
        requireNonNull(id, "id");
        requireNonNull(storeId, "storeId");
        requireText(storeName, "storeName");
        require("WEEKLY_REPORT_READY".equals(eventType)
                        || "WEEKLY_REPORT_REVISED".equals(eventType),
                "unsupported weekly notification eventType");
        requireNonNull(interpretationId, "interpretationId");
        requireNonNull(snapshotId, "snapshotId");
        require(revision > 0, "revision must be positive");
        require(contentSchemaVersion > 0, "contentSchemaVersion must be positive");
        requireNonNull(periodStart, "periodStart");
        requireNonNull(periodEnd, "periodEnd");
        requireText(contentPayload, "contentPayload");
        require(contentHash != null && contentHash.matches("[a-f0-9]{64}"),
                "contentHash must be a lowercase SHA-256");
        requireNonNull(notBefore, "notBefore");
        requireNonNull(expiresAt, "expiresAt");
        require(expiresAt.isAfter(notBefore), "expiresAt must be after notBefore");
    }

    public boolean revised() {
        return "WEEKLY_REPORT_REVISED".equals(eventType);
    }
}
