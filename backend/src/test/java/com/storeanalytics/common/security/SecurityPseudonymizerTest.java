package com.storeanalytics.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.common.config.SecurityTelemetryProperties;
import org.junit.jupiter.api.Test;

class SecurityPseudonymizerTest {

    @Test
    void createsStableNamespaceSeparatedOpaqueReferences() {
        SecurityPseudonymizer pseudonymizer = pseudonymizer();

        String first = pseudonymizer.reference("client", "203.0.113.25");
        String repeated = pseudonymizer.reference("client", "203.0.113.25");
        String anotherNamespace = pseudonymizer.reference(
                "email", "203.0.113.25"
        );

        assertThat(first).isEqualTo(repeated)
                .matches("h1_[0-9a-f]{24}")
                .doesNotContain("203.0.113.25");
        assertThat(anotherNamespace).isNotEqualTo(first);
        assertThat(pseudonymizer.keyId()).isEqualTo("test-v1");
    }

    private SecurityPseudonymizer pseudonymizer() {
        return new SecurityPseudonymizer(new SecurityTelemetryProperties(
                "01234567890123456789012345678901",
                "test-v1"
        ));
    }
}
