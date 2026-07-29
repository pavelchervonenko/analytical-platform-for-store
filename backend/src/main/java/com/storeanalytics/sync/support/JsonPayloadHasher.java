package com.storeanalytics.sync.support;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.storeanalytics.common.config.LiveSkladPayloadLimitsProperties;
import com.storeanalytics.integration.livesklad.exception.LiveSkladPayloadRejectedException;
import com.storeanalytics.integration.livesklad.exception.LiveSkladPayloadRejectedException.Reason;
import com.storeanalytics.integration.livesklad.observability.LiveSkladPayloadRejectionMetrics;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class JsonPayloadHasher {

    private final ObjectMapper objectMapper;
    private final long maxRawPayloadBytes;
    private final LiveSkladPayloadRejectionMetrics rejectionMetrics;
    private final RawPayloadPrivacyFilter privacyFilter;

    JsonPayloadHasher(
            ObjectMapper objectMapper,
            LiveSkladPayloadLimitsProperties payloadLimits
    ) {
        this(
                objectMapper,
                payloadLimits,
                LiveSkladPayloadRejectionMetrics.noop(),
                new RawPayloadPrivacyFilter(objectMapper)
        );
    }

    JsonPayloadHasher(
            ObjectMapper objectMapper,
            LiveSkladPayloadLimitsProperties payloadLimits,
            LiveSkladPayloadRejectionMetrics rejectionMetrics
    ) {
        this(
                objectMapper,
                payloadLimits,
                rejectionMetrics,
                new RawPayloadPrivacyFilter(objectMapper)
        );
    }

    @Autowired
    public JsonPayloadHasher(
            ObjectMapper objectMapper,
            LiveSkladPayloadLimitsProperties payloadLimits,
            LiveSkladPayloadRejectionMetrics rejectionMetrics,
            RawPayloadPrivacyFilter privacyFilter
    ) {
        this.objectMapper = objectMapper;
        this.maxRawPayloadBytes = payloadLimits.maxRawPayloadBytes();
        this.rejectionMetrics = rejectionMetrics;
        this.privacyFilter = privacyFilter;
    }

    public PreparedRawPayload prepare(
            RawPayloadProfile profile,
            JsonNode payload
    ) {
        try {
            JsonNode minimized = privacyFilter.minimize(profile, payload);
            byte[] serialized = objectMapper.writeValueAsBytes(minimized);
            if (serialized.length > maxRawPayloadBytes) {
                rejectionMetrics.record(Reason.RAW_PAYLOAD_TOO_LARGE);
                throw new LiveSkladPayloadRejectedException(
                        Reason.RAW_PAYLOAD_TOO_LARGE,
                        "LiveSklad raw payload exceeds the configured byte limit"
                );
            }
            return new PreparedRawPayload(
                    new String(serialized, StandardCharsets.UTF_8),
                    sha256(minimized),
                    serialized.length
            );
        } catch (JacksonException exception) {
            throw new IllegalArgumentException(
                    "Cannot prepare source payload",
                    exception
            );
        }
    }

    private String sha256(JsonNode payload) {
        try {
            JsonNode canonicalPayload = canonicalize(payload);
            byte[] serialized = objectMapper.writeValueAsString(canonicalPayload)
                    .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(serialized));
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Cannot hash source payload", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            List<String> fieldNames = new ArrayList<>();
            fieldNames.addAll(node.propertyNames());
            fieldNames.stream().sorted().forEach(name -> result.set(name, canonicalize(node.get(name))));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            node.forEach(element -> result.add(canonicalize(element)));
            return result;
        }
        return node.deepCopy();
    }
}
