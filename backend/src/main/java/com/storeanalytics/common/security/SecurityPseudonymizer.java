package com.storeanalytics.common.security;

import com.storeanalytics.common.config.SecurityTelemetryProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public final class SecurityPseudonymizer {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String REFERENCE_VERSION = "h1_";

    private final SecretKeySpec key;
    private final String keyId;

    public SecurityPseudonymizer(SecurityTelemetryProperties properties) {
        this.key = new SecretKeySpec(
                properties.pseudonymKey().getBytes(StandardCharsets.UTF_8),
                ALGORITHM
        );
        this.keyId = properties.pseudonymKeyId();
    }

    public String reference(String namespace, String value) {
        String normalizedNamespace = requireValue(namespace, "namespace");
        String safeValue = value == null ? "" : value;
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            byte[] digest = mac.doFinal(
                    (normalizedNamespace + ":" + safeValue)
                            .getBytes(StandardCharsets.UTF_8)
            );
            return REFERENCE_VERSION + HexFormat.of().formatHex(digest, 0, 12);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA-256 is unavailable", exception);
        }
    }

    public String keyId() {
        return keyId;
    }

    private String requireValue(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}
