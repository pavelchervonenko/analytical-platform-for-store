package com.storeanalytics.interpretation.contract;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class LlmCanonicalJsonCodec {

    private final ObjectMapper objectMapper;

    public LlmCanonicalJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public CanonicalLlmJson canonicalize(String json) {
        String source = requireText(json, "json");
        try {
            JsonNode content = objectMapper.readTree(source);
            require(content != null && content.isObject(),
                    "LLM content must be a JSON object");
            JsonNode canonicalContent = canonicalize(content);
            String canonicalJson = objectMapper.writeValueAsString(canonicalContent);
            return new CanonicalLlmJson(
                    canonicalContent,
                    canonicalJson,
                    sha256(canonicalJson)
            );
        } catch (JacksonException exception) {
            throw new IllegalStateException("LLM content cannot be decoded", exception);
        }
    }

    public JsonNode decodeVerified(String json, String expectedHash) {
        CanonicalLlmJson material = canonicalize(json);
        require(material.contentHash().equals(requireText(expectedHash, "expectedHash")),
                "Published LLM content hash does not match its payload");
        return material.content();
    }

    private JsonNode canonicalize(JsonNode node) {
        JsonNode value = requireNonNull(node, "node");
        if (value.isObject()) {
            var result = objectMapper.createObjectNode();
            value.propertyNames().stream().sorted()
                    .forEach(name -> result.set(name, canonicalize(value.get(name))));
            return result;
        }
        if (value.isArray()) {
            var result = objectMapper.createArrayNode();
            value.forEach(item -> result.add(canonicalize(item)));
            return result;
        }
        return value.deepCopy();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8)
            ));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
