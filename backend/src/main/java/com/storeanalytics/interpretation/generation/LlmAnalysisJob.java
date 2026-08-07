package com.storeanalytics.interpretation.generation;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireJson;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import java.time.Instant;
import java.util.UUID;

public record LlmAnalysisJob(
        UUID id,
        UUID snapshotId,
        int generationRevision,
        LlmAnalysisTriggerType triggerType,
        UUID requestedBy,
        String providerCode,
        String requestedModel,
        String providerConfigVersion,
        int contentSchemaVersion,
        String promptVersion,
        String analysisPolicyVersion,
        String budgetPolicyVersion,
        String generationParameters,
        String inputHash,
        LlmAnalysisJobStatus status,
        LlmAnalysisPhase phase,
        int attemptCount,
        int transportRetryCount,
        int validationRetryCount,
        int maxTransportRetries,
        int maxValidationRetries,
        Instant nextAttemptAt,
        Instant deadlineAt,
        String leaseOwner,
        Instant leaseUntil,
        boolean cancelRequested,
        String terminalReasonCode,
        String errorSummary,
        Instant startedAt,
        Instant finishedAt,
        long version,
        Instant createdAt,
        Instant updatedAt
) {

    public LlmAnalysisJob {
        requireNonNull(id, "id");
        requireNonNull(snapshotId, "snapshotId");
        require(generationRevision > 0, "generationRevision must be positive");
        requireNonNull(triggerType, "triggerType");
        requireText(providerCode, "providerCode");
        requireText(requestedModel, "requestedModel");
        requireText(providerConfigVersion, "providerConfigVersion");
        require(contentSchemaVersion > 0, "contentSchemaVersion must be positive");
        requireText(promptVersion, "promptVersion");
        requireText(analysisPolicyVersion, "analysisPolicyVersion");
        requireText(budgetPolicyVersion, "budgetPolicyVersion");
        requireJson(generationParameters, "generationParameters");
        require(inputHash != null && inputHash.matches("[a-f0-9]{64}"),
                "inputHash must be a lowercase SHA-256");
        requireNonNull(status, "status");
        requireNonNull(phase, "phase");
        require(attemptCount >= 0, "attemptCount must not be negative");
        require(transportRetryCount >= 0, "transportRetryCount must not be negative");
        require(validationRetryCount >= 0, "validationRetryCount must not be negative");
        requireNonNull(nextAttemptAt, "nextAttemptAt");
        requireNonNull(deadlineAt, "deadlineAt");
        requireNonNull(createdAt, "createdAt");
        requireNonNull(updatedAt, "updatedAt");
        require((status == LlmAnalysisJobStatus.RUNNING)
                        == (leaseOwner != null && leaseUntil != null),
                "RUNNING status must match lease state");
        require(deadlineAt.isAfter(createdAt), "deadlineAt must be after createdAt");
    }
}
