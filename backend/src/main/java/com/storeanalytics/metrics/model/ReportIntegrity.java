package com.storeanalytics.metrics.model;

import static com.storeanalytics.common.validation.ModelValidation.require;

public record ReportIntegrity(
        String sourceHash,
        String payloadHash
) {

    private static final String SHA_256_PATTERN = "[0-9a-f]{64}";

    public ReportIntegrity {
        require(sourceHash == null || sourceHash.matches(SHA_256_PATTERN),
                "sourceHash must be a lowercase SHA-256");
        require(payloadHash != null && payloadHash.matches(SHA_256_PATTERN),
                "payloadHash must be a lowercase SHA-256");
    }
}
