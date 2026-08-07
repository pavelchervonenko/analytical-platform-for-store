package com.storeanalytics.notification.daily;

import com.storeanalytics.interpretation.contract.LlmCanonicalJsonCodec;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class DailyStorePulsePayloadCodec {

    private final LlmCanonicalJsonCodec canonicalJsonCodec;
    private final ObjectMapper objectMapper;
    private final DailyStorePulseProperties properties;

    public DailyStorePulsePayloadCodec(
            LlmCanonicalJsonCodec canonicalJsonCodec,
            ObjectMapper objectMapper,
            DailyStorePulseProperties properties
    ) {
        this.canonicalJsonCodec = canonicalJsonCodec;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public String renderVersion() {
        return properties.renderVersion();
    }

    public DailyStorePulsePayload decodeVerified(String json, String expectedHash) {
        try {
            return objectMapper.treeToValue(
                    canonicalJsonCodec.decodeVerified(json, expectedHash),
                    DailyStorePulsePayload.class
            );
        } catch (JacksonException exception) {
            throw new IllegalStateException("Daily store pulse payload is invalid", exception);
        }
    }
}
