package com.storeanalytics.interpretation.contract;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import tools.jackson.databind.JsonNode;

public record CanonicalLlmJson(
        JsonNode content,
        String canonicalJson,
        String contentHash
) {

    public CanonicalLlmJson {
        requireNonNull(content, "content");
        require(content.isObject(), "LLM content must be a JSON object");
        content = content.deepCopy();
        requireText(canonicalJson, "canonicalJson");
        require(contentHash != null && contentHash.matches("[a-f0-9]{64}"),
                "contentHash must be a lowercase SHA-256");
    }
}
