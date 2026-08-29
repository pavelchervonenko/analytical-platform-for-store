package com.storeanalytics.interpretation.review.ai;

import com.storeanalytics.interpretation.review.PersistedWeeklyReviewSnapshot;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WeeklyReviewAiProviderRequestCommand(
        UUID jobId,
        PersistedWeeklyReviewSnapshot snapshot,
        String providerCode,
        String requestedModel,
        BigDecimal temperature,
        int maxOutputTokens,
        Instant now,
        Duration callTimeout,
        Instant jobDeadline,
        List<String> retryViolationCodes
) {
}
