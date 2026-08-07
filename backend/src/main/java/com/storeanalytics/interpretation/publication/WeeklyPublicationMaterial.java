package com.storeanalytics.interpretation.publication;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import java.time.Instant;

public record WeeklyPublicationMaterial(
        String canonicalContent,
        String contentHash,
        Instant validatedAt
) {

    public WeeklyPublicationMaterial {
        requireText(canonicalContent, "canonicalContent");
        require(contentHash != null && contentHash.matches("[a-f0-9]{64}"),
                "contentHash must be a lowercase SHA-256");
        requireNonNull(validatedAt, "validatedAt");
    }
}
