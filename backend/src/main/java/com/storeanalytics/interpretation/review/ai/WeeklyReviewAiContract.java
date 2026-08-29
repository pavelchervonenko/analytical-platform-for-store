package com.storeanalytics.interpretation.review.ai;

/** Immutable resource identifiers for the optional weekly-review AI layer. */
public final class WeeklyReviewAiContract {

    public static final int INPUT_SCHEMA_VERSION = 1;
    public static final int CONTENT_SCHEMA_VERSION = 4;
    public static final String PROMPT_VERSION = "weekly-interpretation-v22";
    public static final String INPUT_SCHEMA =
            "contracts/llm/weekly-review-ai-input-v1.schema.json";
    public static final String CONTENT_SCHEMA =
            "contracts/llm/weekly-review-ai-content-v4.schema.json";
    public static final String SYSTEM_PROMPT =
            "prompts/llm/weekly-interpretation-v22.md";

    private WeeklyReviewAiContract() {
    }
}
