package com.storeanalytics.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SecurityTelemetryPropertiesTest {

    @Test
    void acceptsBoundedKeyAndVersionedIdentifier() {
        String secret = "01234567890123456789012345678901";
        SecurityTelemetryProperties properties = new SecurityTelemetryProperties(
                secret,
                "key-2026-01"
        );

        assertThat(properties.pseudonymKeyId()).isEqualTo("key-2026-01");
        assertThat(properties.toString())
                .contains("pseudonymKey=[REDACTED]")
                .contains("pseudonymKeyId=key-2026-01")
                .doesNotContain(secret);
    }

    @Test
    void rejectsMissingShortOrControlCharacterKeys() {
        assertThatThrownBy(() -> new SecurityTelemetryProperties(null, "v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SecurityTelemetryProperties("short", "v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SecurityTelemetryProperties(
                "0123456789012345678901234567890\n", "v1"
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnboundedKeyIdentifiers() {
        assertThatThrownBy(() -> new SecurityTelemetryProperties(
                "01234567890123456789012345678901",
                "invalid key id"
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
