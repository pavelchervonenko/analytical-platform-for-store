package com.storeanalytics.interpretation.contract;

public final class LlmContractResources {

    public static final int INPUT_SCHEMA_VERSION = 1;
    public static final int CONTENT_SCHEMA_VERSION = 1;
    public static final int NEXT_CONTENT_SCHEMA_VERSION = 2;
    public static final String PROMPT_VERSION = "weekly-interpretation-v3";
    public static final String NEXT_PROMPT_VERSION = "weekly-interpretation-v4";

    public static final String INPUT_SCHEMA =
            "contracts/llm/weekly-interpretation-input-v1.schema.json";
    public static final String CONTENT_SCHEMA =
            "contracts/llm/weekly-interpretation-content-v1.schema.json";
    public static final String NEXT_CONTENT_SCHEMA =
            "contracts/llm/weekly-interpretation-content-v2.schema.json";
    public static final String SYSTEM_PROMPT =
            "prompts/llm/weekly-interpretation-v3.md";
    public static final String NEXT_SYSTEM_PROMPT =
            "prompts/llm/weekly-interpretation-v4.md";

    private LlmContractResources() {
    }

    public static String contentSchema(int version) {
        return switch (version) {
            case CONTENT_SCHEMA_VERSION -> CONTENT_SCHEMA;
            case NEXT_CONTENT_SCHEMA_VERSION -> NEXT_CONTENT_SCHEMA;
            default -> throw new IllegalArgumentException(
                    "Unsupported LLM content schema version: " + version
            );
        };
    }

    public static String systemPrompt(String version) {
        return switch (version) {
            case PROMPT_VERSION -> SYSTEM_PROMPT;
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
        boolean next = NEXT_PROMPT_VERSION.equals(promptVersion)
                && NEXT_CONTENT_SCHEMA_VERSION == contentSchemaVersion;
        return current || next;
    }

}
