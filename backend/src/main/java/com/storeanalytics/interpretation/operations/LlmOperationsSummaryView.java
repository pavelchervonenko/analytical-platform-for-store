package com.storeanalytics.interpretation.operations;

import java.math.BigDecimal;
import java.time.Instant;

public record LlmOperationsSummaryView(
        String attentionLevel,
        long pending,
        long waitingRetry,
        long running,
        long overdueRunning,
        long failed,
        long validationFailed,
        long succeededLast30Days,
        long providerCallsLast30Days,
        long inputTokensLast30Days,
        long outputTokensLast30Days,
        BigDecimal knownCostLast30Days,
        String costCurrency,
        Instant oldestReadyAt
) {
}
