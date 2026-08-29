package com.storeanalytics.interpretation.review.ai;

/** Immutable resource identifiers for the optional weekly-review AI layer. */
public final class WeeklyReviewAiContract {

    public static final String LEGACY_PROMPT_VERSION =
            "weekly-interpretation-v22";
    public static final String PREVIOUS_PROMPT_VERSION =
            "weekly-interpretation-v23";
    public static final int INPUT_SCHEMA_VERSION = 3;
    public static final int CONTENT_SCHEMA_VERSION = 4;
    public static final String PROMPT_VERSION = "weekly-interpretation-v24";
    public static final String INPUT_SCHEMA =
            "contracts/llm/weekly-review-ai-input-v3.schema.json";
    public static final String CONTENT_SCHEMA =
            "contracts/llm/weekly-review-ai-content-v4.schema.json";
    public static final String SYSTEM_PROMPT =
            "prompts/llm/weekly-interpretation-v24.md";

    public static boolean isActive(String promptVersion, int schemaVersion) {
        return PROMPT_VERSION.equals(promptVersion)
                && CONTENT_SCHEMA_VERSION == schemaVersion;
    }

    public static boolean isReadable(String promptVersion, int schemaVersion) {
        return CONTENT_SCHEMA_VERSION == schemaVersion
                && (PROMPT_VERSION.equals(promptVersion)
                || PREVIOUS_PROMPT_VERSION.equals(promptVersion)
                || LEGACY_PROMPT_VERSION.equals(promptVersion));
    }

    public static boolean hasBackendOwnedActionTitle(String promptVersion) {
        return PROMPT_VERSION.equals(promptVersion)
                || PREVIOUS_PROMPT_VERSION.equals(promptVersion);
    }

    private WeeklyReviewAiContract() {
    }
}
