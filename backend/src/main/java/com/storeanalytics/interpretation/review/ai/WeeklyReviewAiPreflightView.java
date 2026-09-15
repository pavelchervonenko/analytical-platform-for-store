package com.storeanalytics.interpretation.review.ai;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Sanitized network-free approval material for one exact immutable snapshot. */
public record WeeklyReviewAiPreflightView(
        WeeklyReviewAiPreflightSnapshot snapshot,
        WeeklyReviewAiPreflightContract contract,
        WeeklyReviewAiPrivacySummary privacy,
        WeeklyReviewAiPreflightRequest request,
        WeeklyReviewAiPreflightBudget budget,
        WeeklyReviewAiExistingState existing,
        boolean approvalEligible,
        boolean generationEnabled,
        boolean workerEnabled
) {

    public record WeeklyReviewAiPreflightSnapshot(
            UUID snapshotId,
            UUID storeId,
            int revision,
            LocalDate periodStart,
            LocalDate periodEnd,
            String timezone,
            String reportState,
            String contentHash
    ) {
    }

    public record WeeklyReviewAiPreflightContract(
            String promptVersion,
            int inputSchemaVersion,
            int selectionSchemaVersion,
            int contentSchemaVersion
    ) {
    }

    public record WeeklyReviewAiPrivacySummary(
            String verdict,
            String scope,
            boolean employeeScopeIncluded,
            boolean rawInputIncluded
    ) {
    }

    public record WeeklyReviewAiPreflightRequest(
            String providerCode,
            String modelVersion,
            String providerCredentialCheck,
            String inputHash,
            String requestHash,
            int estimatedInputTokens,
            int maximumOutputTokens,
            int contextWindowTokens,
            int factorCount,
            int actionCount,
            int evidenceCount
    ) {
    }

    public record WeeklyReviewAiPreflightBudget(
            BigDecimal estimatedMaximumCostPerCall,
            int maximumAllowedProviderCalls,
            int recommendedCanaryProviderCalls,
            BigDecimal estimatedMaximumCostAtAllowedCalls,
            String costCurrency,
            BigDecimal configuredPerCallLimit,
            BigDecimal actualCostToday,
            BigDecimal configuredDailyLimit
    ) {
    }

    public record WeeklyReviewAiExistingState(
            UUID jobId,
            String jobStatus,
            UUID enrichmentId,
            String enrichmentInputHash,
            String enrichmentContentHash
    ) {
    }
}
