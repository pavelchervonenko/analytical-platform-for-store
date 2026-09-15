package com.storeanalytics.interpretation.review.ai;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/** Exact operator approval bound to a prior network-free preflight response. */
public record WeeklyReviewAiGenerationApproval(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                pattern = "[a-f0-9]{64}")
        String snapshotContentHash,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                pattern = "[a-f0-9]{64}")
        String inputHash,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                pattern = "[a-f0-9]{64}")
        String requestHash,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                minimum = "1", maximum = "2")
        int approvedMaximumProviderCalls,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                minimum = "0", exclusiveMinimum = true)
        BigDecimal approvedMaximumTotalCost,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                allowableValues = "RUB")
        String costCurrency
) {

    public WeeklyReviewAiGenerationApproval {
        requireHash(snapshotContentHash, "snapshotContentHash");
        requireHash(inputHash, "inputHash");
        requireHash(requestHash, "requestHash");
        require(approvedMaximumProviderCalls >= 1
                        && approvedMaximumProviderCalls <= 2,
                "approvedMaximumProviderCalls must be 1 or 2");
        BigDecimal cost = requireNonNull(
                approvedMaximumTotalCost, "approvedMaximumTotalCost"
        );
        require(cost.signum() > 0, "approvedMaximumTotalCost must be positive");
        require(cost.compareTo(new BigDecimal("2000.00")) <= 0,
                "approvedMaximumTotalCost exceeds safe bound");
        require("RUB".equals(costCurrency), "costCurrency must be RUB");
    }

    private static void requireHash(String value, String field) {
        require(value != null && value.matches("[a-f0-9]{64}"),
                field + " must be a lowercase SHA-256");
    }
}
