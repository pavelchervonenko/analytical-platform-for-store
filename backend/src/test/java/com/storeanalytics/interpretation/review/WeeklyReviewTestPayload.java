package com.storeanalytics.interpretation.review;

import java.time.LocalDate;
import java.util.UUID;

public final class WeeklyReviewTestPayload {

    private WeeklyReviewTestPayload() {
    }

    public static String snapshotPayload(
            UUID snapshotId,
            LocalDate periodStart,
            int revision,
            String reportState
    ) {
        return """
                {
                  "contractVersion": 2,
                  "versions": {
                    "metricsPolicy": "metrics-v4",
                    "snapshotPolicy": "snapshot-v7",
                    "qualityPolicy": "quality-v4"
                  },
                  "period": {
                    "timezone": "Europe/Moscow",
                    "current": {"start": "%s", "end": "%s"}
                  },
                  "reportState": "%s",
                  "provenance": {
                    "snapshotPublicId": "%s",
                    "revision": %d
                  }
                }
                """.formatted(
                periodStart, periodStart.plusDays(6), reportState,
                snapshotId, revision
        );
    }
}
