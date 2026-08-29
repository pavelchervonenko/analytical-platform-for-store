package com.storeanalytics.interpretation.review.ai;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

public record PersistedWeeklyReviewAiEnrichment(
        UUID id,
        UUID snapshotId,
        String promptVersion,
        int contentSchemaVersion,
        String inputHash,
        WeeklyReviewAiContent content,
        String canonicalContent,
        String contentHash,
        Instant validatedAt,
        Instant publishedAt,
        Instant createdAt
) {

    private static final Pattern SHA_256 = Pattern.compile("^[a-f0-9]{64}$");

    public PersistedWeeklyReviewAiEnrichment {
        requireNonNull(id, "id");
        requireNonNull(snapshotId, "snapshotId");
        requireText(promptVersion, "promptVersion");
        require(contentSchemaVersion > 0,
                "contentSchemaVersion must be positive");
        require(SHA_256.matcher(requireText(inputHash, "inputHash")).matches(),
                "inputHash must be SHA-256");
        requireNonNull(content, "content");
        requireText(canonicalContent, "canonicalContent");
        require(SHA_256.matcher(requireText(contentHash, "contentHash")).matches(),
                "contentHash must be SHA-256");
        requireNonNull(validatedAt, "validatedAt");
        requireNonNull(publishedAt, "publishedAt");
        require(!publishedAt.isBefore(validatedAt),
                "publishedAt must not precede validatedAt");
        requireNonNull(createdAt, "createdAt");
    }

    public WeeklyReviewAiValidationResult validationResult() {
        return WeeklyReviewAiValidationResult.semanticallyValid(
                content, canonicalContent
        );
    }
}
