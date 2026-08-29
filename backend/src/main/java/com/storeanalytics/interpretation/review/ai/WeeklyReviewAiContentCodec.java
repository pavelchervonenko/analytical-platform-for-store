package com.storeanalytics.interpretation.review.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Component
final class WeeklyReviewAiContentCodec {

    private final ObjectMapper mapper;
    private final ObjectWriter writer;

    WeeklyReviewAiContentCodec() {
        mapper = JsonMapper.builder()
                .findAndAddModules()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build();
        writer = mapper.writer();
    }

    String canonical(WeeklyReviewAiContent content) {
        return encode(content, "AI enrichment could not be encoded");
    }

    String canonical(WeeklyReviewAiInput input) {
        return encode(input, "AI enrichment input could not be encoded");
    }

    WeeklyReviewAiContent deserialize(String payload) {
        try {
            return mapper.readValue(payload, WeeklyReviewAiContent.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "AI enrichment payload is not readable", exception
            );
        }
    }

    String hash(String canonicalPayload) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            canonicalPayload.getBytes(StandardCharsets.UTF_8)
                    )
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String encode(Object value, String message) {
        try {
            return writer.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException(message, exception);
        }
    }
}
