package com.storeanalytics.interpretation.review.ai;

import java.util.List;

/** Immutable resource identifiers for the optional weekly-review AI layer. */
public final class WeeklyReviewAiContract {

    public static final String LEGACY_PROMPT_VERSION =
            "weekly-interpretation-v22";
    public static final String V23_PROMPT_VERSION =
            "weekly-interpretation-v23";
    public static final String PREVIOUS_PROMPT_VERSION =
            "weekly-interpretation-v24";
    public static final int INPUT_SCHEMA_VERSION = 4;
    public static final int CONTENT_SCHEMA_VERSION = 4;
    public static final int SELECTION_SCHEMA_VERSION = 1;
    public static final String PROMPT_VERSION = "weekly-interpretation-v25";
    public static final String INPUT_SCHEMA =
            "contracts/llm/weekly-review-ai-input-v4.schema.json";
    public static final String SELECTION_SCHEMA =
            "contracts/llm/weekly-review-ai-selection-v1.schema.json";
    public static final String CONTENT_SCHEMA =
            "contracts/llm/weekly-review-ai-content-v4.schema.json";
    public static final String SYSTEM_PROMPT =
            "prompts/llm/weekly-interpretation-v25.md";

    public static List<String> readablePromptVersions() {
        return List.of(
                PROMPT_VERSION,
                PREVIOUS_PROMPT_VERSION,
                V23_PROMPT_VERSION,
                LEGACY_PROMPT_VERSION
        );
    }

    public static boolean isActive(String promptVersion, int schemaVersion) {
        return PROMPT_VERSION.equals(promptVersion)
                && CONTENT_SCHEMA_VERSION == schemaVersion;
    }

    public static boolean isReadable(String promptVersion, int schemaVersion) {
        return CONTENT_SCHEMA_VERSION == schemaVersion
                && readablePromptVersions().contains(promptVersion);
    }

    public static boolean hasBackendOwnedActionTitle(String promptVersion) {
        return PROMPT_VERSION.equals(promptVersion)
                || PREVIOUS_PROMPT_VERSION.equals(promptVersion)
                || V23_PROMPT_VERSION.equals(promptVersion);
    }

    private WeeklyReviewAiContract() {
    }
}
