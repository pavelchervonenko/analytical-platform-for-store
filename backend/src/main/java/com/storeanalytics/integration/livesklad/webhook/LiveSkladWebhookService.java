package com.storeanalytics.integration.livesklad.webhook;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.stereotype.Service;

@Service
class LiveSkladWebhookService {

    static final String VERIFICATION_HEADER = "X-LiveSklad-Verification";
    private static final int MAX_EVENT_ID_LENGTH = 256;
    private static final int MAX_VERIFICATION_LENGTH = 512;

    private final ObjectMapper objectMapper;
    private final LiveSkladWebhookStore store;
    private final Clock clock;

    LiveSkladWebhookService(
            ObjectMapper objectMapper,
            LiveSkladWebhookStore store,
            Clock clock
    ) {
        this.objectMapper = objectMapper;
        this.store = store;
        this.clock = clock;
    }

    LiveSkladWebhookAcceptance receive(
            LiveSkladWebhookKind kind,
            byte[] body,
            String verification
    ) {
        if (body == null || body.length == 0) {
            return verification(verification);
        }

        JsonNode payload;
        try {
            payload = objectMapper.readTree(body);
        } catch (JacksonException exception) {
            return LiveSkladWebhookAcceptance.rejected();
        }
        if (payload == null || !payload.isObject()) {
            return LiveSkladWebhookAcceptance.rejected();
        }

        String eventId = value(payload.get("eventId"));
        if (eventId == null || eventId.isBlank()) {
            return verification(firstText(
                    verification,
                    value(payload.get("verification")),
                    value(payload.get("verificationCode")),
                    value(payload.get("challenge")),
                    value(payload.get("value"))
            ));
        }
        eventId = eventId.trim();
        if (eventId.length() > MAX_EVENT_ID_LENGTH) {
            return LiveSkladWebhookAcceptance.rejected();
        }

        JsonNode action = payload.path("action");
        String canonicalPayload;
        try {
            canonicalPayload = objectMapper.writeValueAsString(payload);
        } catch (JacksonException exception) {
            return LiveSkladWebhookAcceptance.rejected();
        }
        store.record(new LiveSkladWebhookReceipt(
                kind,
                eventId,
                value(action.get("id")),
                value(action.get("groupId")),
                value(action.get("name")),
                canonicalPayload,
                sha256(canonicalPayload),
                Instant.now(clock)
        ));
        return LiveSkladWebhookAcceptance.accepted("OK");
    }

    private LiveSkladWebhookAcceptance verification(String candidate) {
        if (!validVerification(candidate)) {
            return LiveSkladWebhookAcceptance.rejected();
        }
        return LiveSkladWebhookAcceptance.accepted(candidate);
    }

    private boolean validVerification(String candidate) {
        if (candidate == null || candidate.isBlank()
                || candidate.length() > MAX_VERIFICATION_LENGTH) {
            return false;
        }
        return candidate.chars().noneMatch(character ->
                character == '\r' || character == '\n' || character == 0);
    }

    private String firstText(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private String value(JsonNode node) {
        if (node == null || node.isNull() || !node.isValueNode()) {
            return null;
        }
        return node.asText();
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8)
            );
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
