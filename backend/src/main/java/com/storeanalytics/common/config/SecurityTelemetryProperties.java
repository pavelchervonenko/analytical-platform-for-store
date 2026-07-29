package com.storeanalytics.common.config;

import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.security.telemetry")
public record SecurityTelemetryProperties(
        String pseudonymKey,
        String pseudonymKeyId
) {

    private static final int MINIMUM_KEY_LENGTH = 32;
    private static final int MAXIMUM_KEY_LENGTH = 256;
    private static final Pattern KEY_ID = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._-]{0,31}"
    );

    public SecurityTelemetryProperties {
        if (pseudonymKey == null
                || pseudonymKey.length() < MINIMUM_KEY_LENGTH
                || pseudonymKey.length() > MAXIMUM_KEY_LENGTH
                || pseudonymKey.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "Security telemetry pseudonym key must contain 32-256 characters"
            );
        }
        pseudonymKeyId = pseudonymKeyId == null ? "" : pseudonymKeyId.trim();
        if (!KEY_ID.matcher(pseudonymKeyId).matches()) {
            throw new IllegalArgumentException(
                    "Security telemetry pseudonym key ID has an invalid format"
            );
        }
    }

    @Override
    public String toString() {
        return "SecurityTelemetryProperties["
                + "pseudonymKey=[REDACTED]"
                + ", pseudonymKeyId=" + pseudonymKeyId + "]";
    }
}
