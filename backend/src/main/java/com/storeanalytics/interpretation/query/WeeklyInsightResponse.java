package com.storeanalytics.interpretation.query;

import java.time.Instant;
import java.util.UUID;

public record WeeklyInsightResponse(
        WeeklyInsightPeriodView period,
        WeeklyInsightState state,
        WeeklyInsightReasonCode reasonCode,
        String message,
        Instant statusUpdatedAt,
        Instant nextRefreshAt,
        UUID interpretationId,
        Integer revision,
        Instant publishedAt,
        Instant sourceDataUpdatedAt,
        WeeklyInsightRevisionState revisionState,
        WeeklyInsightContentView content,
        WeeklyInsightFallbackView fallback
) {
}
