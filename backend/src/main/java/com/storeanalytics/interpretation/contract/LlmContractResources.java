package com.storeanalytics.interpretation.contract;

public final class LlmContractResources {

    public static final int INPUT_SCHEMA_VERSION = 1;
    public static final int CONTENT_SCHEMA_VERSION = 1;
    public static final int NEXT_CONTENT_SCHEMA_VERSION = 2;
    public static final int PRIMARY_SIGNAL_CONTENT_SCHEMA_VERSION = 3;
    public static final String PROMPT_VERSION = "weekly-interpretation-v3";
    public static final String NEXT_PROMPT_VERSION = "weekly-interpretation-v4";

    public static final String CONCISE_PROMPT_VERSION =
            "weekly-interpretation-v5";
    public static final String REVISED_CONCISE_PROMPT_VERSION =
            "weekly-interpretation-v6";
    public static final String STRICT_CONCISE_PROMPT_VERSION =
            "weekly-interpretation-v7";
    public static final String ACTIONABLE_CONCISE_PROMPT_VERSION =
            "weekly-interpretation-v8";
    public static final String EVIDENCE_GUARDED_PROMPT_VERSION =
            "weekly-interpretation-v9";
    public static final String HARDENED_EVIDENCE_PROMPT_VERSION =
            "weekly-interpretation-v10";
    public static final String NARRATIVE_GUARDED_PROMPT_VERSION =
            "weekly-interpretation-v11";
    public static final String CAUSAL_NARRATIVE_GUARDED_PROMPT_VERSION =
            "weekly-interpretation-v12";
    public static final String PRIMARY_SIGNAL_PROMPT_VERSION =
            "weekly-interpretation-v13";
    public static final String STRUCTURED_SUMMARY_PROMPT_VERSION =
            "weekly-interpretation-v14";
    public static final String TEAM_GUARDED_STRUCTURED_SUMMARY_PROMPT_VERSION =
            "weekly-interpretation-v15";
    public static final String MATRIX_HARDENED_STRUCTURED_SUMMARY_PROMPT_VERSION =
            "weekly-interpretation-v16";
    public static final String PRODUCTION_HARDENED_STRUCTURED_SUMMARY_PROMPT_VERSION =
            "weekly-interpretation-v17";
    public static final String DETERMINISTIC_NARRATIVE_PROMPT_VERSION =
            "weekly-interpretation-v18";
    public static final String PRIVACY_REDUCED_PROMPT_VERSION =
            "weekly-interpretation-v19";
    public static final String MODERATION_SAFE_PRIVACY_REDUCED_PROMPT_VERSION =
            "weekly-interpretation-v20";
    public static final String BOUNDED_PRIVACY_REDUCED_PROMPT_VERSION =
            "weekly-interpretation-v21";
    public static final String INPUT_SCHEMA =
            "contracts/llm/weekly-interpretation-input-v1.schema.json";
    public static final String CONTENT_SCHEMA =
            "contracts/llm/weekly-interpretation-content-v1.schema.json";
    public static final String NEXT_CONTENT_SCHEMA =
            "contracts/llm/weekly-interpretation-content-v2.schema.json";
    public static final String PRIMARY_SIGNAL_CONTENT_SCHEMA =
            "contracts/llm/weekly-interpretation-content-v3.schema.json";
    public static final String SYSTEM_PROMPT =
            "prompts/llm/weekly-interpretation-v3.md";
    public static final String NEXT_SYSTEM_PROMPT =
            "prompts/llm/weekly-interpretation-v4.md";
    public static final String CONCISE_SYSTEM_PROMPT =
            "prompts/llm/weekly-interpretation-v5.md";
    public static final String REVISED_CONCISE_SYSTEM_PROMPT =
            "prompts/llm/weekly-interpretation-v6.md";
    public static final String STRICT_CONCISE_SYSTEM_PROMPT =
            "prompts/llm/weekly-interpretation-v7.md";
    public static final String ACTIONABLE_CONCISE_SYSTEM_PROMPT =
            "prompts/llm/weekly-interpretation-v8.md";
    public static final String EVIDENCE_GUARDED_SYSTEM_PROMPT =
            "prompts/llm/weekly-interpretation-v9.md";
    public static final String HARDENED_EVIDENCE_SYSTEM_PROMPT =
            "prompts/llm/weekly-interpretation-v10.md";
    public static final String NARRATIVE_GUARDED_SYSTEM_PROMPT =
            "prompts/llm/weekly-interpretation-v11.md";
    public static final String CAUSAL_NARRATIVE_GUARDED_SYSTEM_PROMPT =
            "prompts/llm/weekly-interpretation-v12.md";
    public static final String PRIMARY_SIGNAL_SYSTEM_PROMPT =
            "prompts/llm/weekly-interpretation-v13.md";
    public static final String STRUCTURED_SUMMARY_SYSTEM_PROMPT =
            "prompts/llm/weekly-interpretation-v14.md";
    public static final String TEAM_GUARDED_STRUCTURED_SUMMARY_SYSTEM_PROMPT =
            "prompts/llm/weekly-interpretation-v15.md";
    public static final String MATRIX_HARDENED_STRUCTURED_SUMMARY_SYSTEM_PROMPT =
            "prompts/llm/weekly-interpretation-v16.md";
    public static final String PRODUCTION_HARDENED_STRUCTURED_SUMMARY_SYSTEM_PROMPT =
            "prompts/llm/weekly-interpretation-v17.md";
    public static final String DETERMINISTIC_NARRATIVE_SYSTEM_PROMPT =
            "prompts/llm/weekly-interpretation-v18.md";
    public static final String PRIVACY_REDUCED_SYSTEM_PROMPT =
            "prompts/llm/weekly-interpretation-v19.md";
    public static final String MODERATION_SAFE_PRIVACY_REDUCED_SYSTEM_PROMPT =
            "prompts/llm/weekly-interpretation-v20.md";
    public static final String BOUNDED_PRIVACY_REDUCED_SYSTEM_PROMPT =
            "prompts/llm/weekly-interpretation-v21.md";

    private LlmContractResources() {
    }

    public static String contentSchema(int version) {
        return switch (version) {
            case CONTENT_SCHEMA_VERSION -> CONTENT_SCHEMA;
            case NEXT_CONTENT_SCHEMA_VERSION -> NEXT_CONTENT_SCHEMA;
            case PRIMARY_SIGNAL_CONTENT_SCHEMA_VERSION ->
                    PRIMARY_SIGNAL_CONTENT_SCHEMA;
            default -> throw new IllegalArgumentException(
                    "Unsupported LLM content schema version: " + version
            );
        };
    }

    public static String systemPrompt(String version) {
        return switch (version) {
            case PROMPT_VERSION -> SYSTEM_PROMPT;
            case CONCISE_PROMPT_VERSION -> CONCISE_SYSTEM_PROMPT;
            case REVISED_CONCISE_PROMPT_VERSION ->
                    REVISED_CONCISE_SYSTEM_PROMPT;
            case STRICT_CONCISE_PROMPT_VERSION ->
                    STRICT_CONCISE_SYSTEM_PROMPT;
            case ACTIONABLE_CONCISE_PROMPT_VERSION ->
                    ACTIONABLE_CONCISE_SYSTEM_PROMPT;
            case EVIDENCE_GUARDED_PROMPT_VERSION ->
                    EVIDENCE_GUARDED_SYSTEM_PROMPT;
            case HARDENED_EVIDENCE_PROMPT_VERSION ->
                    HARDENED_EVIDENCE_SYSTEM_PROMPT;
            case NARRATIVE_GUARDED_PROMPT_VERSION ->
                    NARRATIVE_GUARDED_SYSTEM_PROMPT;
            case CAUSAL_NARRATIVE_GUARDED_PROMPT_VERSION ->
                    CAUSAL_NARRATIVE_GUARDED_SYSTEM_PROMPT;
            case PRIMARY_SIGNAL_PROMPT_VERSION ->
                    PRIMARY_SIGNAL_SYSTEM_PROMPT;
            case TEAM_GUARDED_STRUCTURED_SUMMARY_PROMPT_VERSION ->
                    TEAM_GUARDED_STRUCTURED_SUMMARY_SYSTEM_PROMPT;
            case MATRIX_HARDENED_STRUCTURED_SUMMARY_PROMPT_VERSION ->
                    MATRIX_HARDENED_STRUCTURED_SUMMARY_SYSTEM_PROMPT;
            case PRODUCTION_HARDENED_STRUCTURED_SUMMARY_PROMPT_VERSION ->
                    PRODUCTION_HARDENED_STRUCTURED_SUMMARY_SYSTEM_PROMPT;
            case DETERMINISTIC_NARRATIVE_PROMPT_VERSION ->
                    DETERMINISTIC_NARRATIVE_SYSTEM_PROMPT;
            case PRIVACY_REDUCED_PROMPT_VERSION ->
                    PRIVACY_REDUCED_SYSTEM_PROMPT;
            case MODERATION_SAFE_PRIVACY_REDUCED_PROMPT_VERSION ->
                    MODERATION_SAFE_PRIVACY_REDUCED_SYSTEM_PROMPT;
            case BOUNDED_PRIVACY_REDUCED_PROMPT_VERSION ->
                    BOUNDED_PRIVACY_REDUCED_SYSTEM_PROMPT;
            case STRUCTURED_SUMMARY_PROMPT_VERSION ->
                    STRUCTURED_SUMMARY_SYSTEM_PROMPT;
            case NEXT_PROMPT_VERSION -> NEXT_SYSTEM_PROMPT;
            default -> throw new IllegalArgumentException(
                    "Unsupported LLM prompt version: " + version
            );
        };
    }

    public static boolean isSupportedPair(
            String promptVersion,
            int contentSchemaVersion
    ) {
        boolean current = PROMPT_VERSION.equals(promptVersion)
                && CONTENT_SCHEMA_VERSION == contentSchemaVersion;
        boolean flat = (NEXT_PROMPT_VERSION.equals(promptVersion)
                || CONCISE_PROMPT_VERSION.equals(promptVersion)
                || REVISED_CONCISE_PROMPT_VERSION.equals(promptVersion)
                || STRICT_CONCISE_PROMPT_VERSION.equals(promptVersion)
                || ACTIONABLE_CONCISE_PROMPT_VERSION.equals(promptVersion)
                || EVIDENCE_GUARDED_PROMPT_VERSION.equals(promptVersion)
                || HARDENED_EVIDENCE_PROMPT_VERSION.equals(promptVersion)
                || NARRATIVE_GUARDED_PROMPT_VERSION.equals(promptVersion)
                || CAUSAL_NARRATIVE_GUARDED_PROMPT_VERSION.equals(promptVersion))
                && NEXT_CONTENT_SCHEMA_VERSION == contentSchemaVersion;
        boolean primarySignal = isPrimarySignalPrompt(promptVersion)
                && PRIMARY_SIGNAL_CONTENT_SCHEMA_VERSION == contentSchemaVersion;
        return current || flat || primarySignal;
    }

    public static boolean isConcisePrompt(String promptVersion) {
        return CONCISE_PROMPT_VERSION.equals(promptVersion)
                || REVISED_CONCISE_PROMPT_VERSION.equals(promptVersion)
                || isStrictConcisePrompt(promptVersion);
    }

    public static boolean isStrictConcisePrompt(String promptVersion) {
        return STRICT_CONCISE_PROMPT_VERSION.equals(promptVersion)
                || ACTIONABLE_CONCISE_PROMPT_VERSION.equals(promptVersion)
                || isEvidenceGuardedPrompt(promptVersion);
    }

    public static boolean isStructuredSummaryPrompt(String promptVersion) {
        return STRUCTURED_SUMMARY_PROMPT_VERSION.equals(promptVersion)
                || TEAM_GUARDED_STRUCTURED_SUMMARY_PROMPT_VERSION.equals(
                promptVersion
        ) || MATRIX_HARDENED_STRUCTURED_SUMMARY_PROMPT_VERSION.equals(
                promptVersion
        ) || PRODUCTION_HARDENED_STRUCTURED_SUMMARY_PROMPT_VERSION.equals(
                promptVersion
        ) || DETERMINISTIC_NARRATIVE_PROMPT_VERSION.equals(
                promptVersion
        ) || isPrivacyReducedPrompt(promptVersion);
    }

    public static boolean isPrivacyReducedPrompt(String promptVersion) {
        return PRIVACY_REDUCED_PROMPT_VERSION.equals(promptVersion)
                || MODERATION_SAFE_PRIVACY_REDUCED_PROMPT_VERSION.equals(
                promptVersion
        ) || isBoundedPrivacyReducedPrompt(promptVersion);
    }

    public static boolean isBoundedPrivacyReducedPrompt(
            String promptVersion
    ) {
        return BOUNDED_PRIVACY_REDUCED_PROMPT_VERSION.equals(
                promptVersion
        );
    }

    public static boolean isEvidenceGuardedPrompt(String promptVersion) {
        return EVIDENCE_GUARDED_PROMPT_VERSION.equals(promptVersion)
                || HARDENED_EVIDENCE_PROMPT_VERSION.equals(promptVersion)
                || NARRATIVE_GUARDED_PROMPT_VERSION.equals(promptVersion)
                || CAUSAL_NARRATIVE_GUARDED_PROMPT_VERSION.equals(promptVersion)
                || isPrimarySignalPrompt(promptVersion);
    }

    private static boolean isPrimarySignalPrompt(String promptVersion) {
        return PRIMARY_SIGNAL_PROMPT_VERSION.equals(promptVersion)
                || isStructuredSummaryPrompt(promptVersion);
    }

}
