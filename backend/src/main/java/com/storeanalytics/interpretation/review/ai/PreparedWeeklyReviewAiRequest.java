package com.storeanalytics.interpretation.review.ai;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.interpretation.generation.LlmProviderRequest;

public record PreparedWeeklyReviewAiRequest(
        LlmProviderRequest request,
        String requestHash,
        WeeklyReviewAiInput input,
        String inputHash
) {

    public PreparedWeeklyReviewAiRequest {
        requireNonNull(request, "request");
        requireHash(requestHash, "requestHash");
        requireNonNull(input, "input");
        requireHash(inputHash, "inputHash");
    }

    private static void requireHash(String value, String field) {
        require(value != null && value.matches("[a-f0-9]{64}"),
                field + " must be a lowercase SHA-256");
    }
}
