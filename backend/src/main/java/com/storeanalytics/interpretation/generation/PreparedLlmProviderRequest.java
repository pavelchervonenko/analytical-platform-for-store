package com.storeanalytics.interpretation.generation;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

public record PreparedLlmProviderRequest(
        LlmProviderRequest request,
        String requestHash
) {

    public PreparedLlmProviderRequest {
        requireNonNull(request, "request");
        require(requestHash != null && requestHash.matches("[a-f0-9]{64}"),
                "requestHash must be a lowercase SHA-256");
    }
}
