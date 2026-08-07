package com.storeanalytics.interpretation.generation;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LlmAnalysisAttempt(
        UUID id,
        UUID jobId,
        int attemptNumber,
        LlmAnalysisAttemptType attemptType,
        LlmAnalysisAttemptStatus status,
        String providerCode,
        String requestedModel,
        String resolvedModel,
        String providerRequestId,
        String requestHash,
        String responseHash,
        String responseBody,
        String validatedResponseHash,
        String validatedResponseBody,
        String validationViolations,
        Integer inputTokens,
        Integer outputTokens,
        Integer cachedInputTokens,
        Integer reasoningTokens,
        Integer totalTokens,
        BigDecimal costAmount,
        String costCurrency,
        Long latencyMs,
        Integer httpStatus,
        String errorCode,
        String errorSummary,
        Instant startedAt,
        Instant responseReceivedAt,
        Instant finishedAt,
        Instant createdAt
) {

    public LlmAnalysisAttempt {
        requireNonNull(id, "id");
        requireNonNull(jobId, "jobId");
        require(attemptNumber > 0, "attemptNumber must be positive");
        requireNonNull(attemptType, "attemptType");
        requireNonNull(status, "status");
        requireText(providerCode, "providerCode");
        requireText(requestedModel, "requestedModel");
        require(requestHash != null && requestHash.matches("[a-f0-9]{64}"),
                "requestHash must be a lowercase SHA-256");
        require(responseHash == null || responseHash.matches("[a-f0-9]{64}"),
                "responseHash must be a lowercase SHA-256");
        require(validatedResponseHash == null
                        || validatedResponseHash.matches("[a-f0-9]{64}"),
                "validatedResponseHash must be a lowercase SHA-256");
        require((validatedResponseHash == null) == (validatedResponseBody == null),
                "validated response body and hash must be present together");
        requireNonNull(startedAt, "startedAt");
        requireNonNull(createdAt, "createdAt");
        boolean open = status == LlmAnalysisAttemptStatus.STARTED
                || status == LlmAnalysisAttemptStatus.RESPONSE_RECEIVED;
        require(open == (finishedAt == null),
                "open attempt status must match finishedAt");
        require(responseReceivedAt == null || !responseReceivedAt.isBefore(startedAt),
                "responseReceivedAt must not precede startedAt");
        require(finishedAt == null || !finishedAt.isBefore(startedAt),
                "finishedAt must not precede startedAt");
    }
}
