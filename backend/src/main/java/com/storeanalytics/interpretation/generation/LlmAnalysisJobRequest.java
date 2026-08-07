package com.storeanalytics.interpretation.generation;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireJson;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import java.time.Instant;
import java.util.UUID;

public record LlmAnalysisJobRequest(
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
        int maxTransportRetries,
        int maxValidationRetries,
        Instant deadlineAt
) {

    public LlmAnalysisJobRequest {
        requireNonNull(snapshotId, "snapshotId");
        require(generationRevision > 0, "generationRevision must be positive");
        requireNonNull(triggerType, "triggerType");
        providerCode = boundedText(providerCode, 40, "providerCode");
        requestedModel = boundedText(requestedModel, 300, "requestedModel");
        providerConfigVersion = boundedText(
                providerConfigVersion, 100, "providerConfigVersion"
        );
        require(contentSchemaVersion > 0, "contentSchemaVersion must be positive");
        promptVersion = boundedText(promptVersion, 100, "promptVersion");
        analysisPolicyVersion = boundedText(
                analysisPolicyVersion, 100, "analysisPolicyVersion"
        );
        budgetPolicyVersion = boundedText(
                budgetPolicyVersion, 100, "budgetPolicyVersion"
        );
        generationParameters = requireJson(generationParameters, "generationParameters");
        require(inputHash != null && inputHash.matches("[a-f0-9]{64}"),
                "inputHash must be a lowercase SHA-256");
        require(maxTransportRetries >= 0 && maxTransportRetries <= 10,
                "maxTransportRetries must be between 0 and 10");
        require(maxValidationRetries >= 0 && maxValidationRetries <= 1,
                "maxValidationRetries must be between 0 and 1");
        requireNonNull(deadlineAt, "deadlineAt");
    }

    private static String boundedText(String value, int limit, String field) {
        String text = requireText(value, field).trim();
        require(text.length() <= limit, field + " must not exceed " + limit + " characters");
        return text;
    }
}
