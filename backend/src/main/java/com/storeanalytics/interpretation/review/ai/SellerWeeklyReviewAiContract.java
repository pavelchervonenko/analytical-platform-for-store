package com.storeanalytics.interpretation.review.ai;

/** New seller input/prompt; the bounded selection and rendered content shapes are unchanged. */
public final class SellerWeeklyReviewAiContract {
    public static final int INPUT_VERSION = 5;
    public static final String PROMPT_VERSION = "weekly-interpretation-v26";
    public static final String INPUT_SCHEMA = "contracts/llm/weekly-review-ai-input-v5.schema.json";
    public static final String SYSTEM_PROMPT = "prompts/llm/weekly-interpretation-v26.md";

    private SellerWeeklyReviewAiContract() {
    }

    public static boolean isActive(String prompt, int contentVersion) {
        return PROMPT_VERSION.equals(prompt) && contentVersion == WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION;
    }
}
