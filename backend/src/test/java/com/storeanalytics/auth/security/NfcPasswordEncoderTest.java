package com.storeanalytics.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class NfcPasswordEncoderTest {

    private static final String DECOMPOSED_PASSWORD = "Cafe\u0301 password 2026";
    private static final String NFC_PASSWORD = "Caf\u00e9 password 2026";

    private final PasswordEncoder delegate = new DelegatingPasswordEncoder(
            "bcrypt",
            Map.of("bcrypt", new BCryptPasswordEncoder(4))
    );
    private final NfcPasswordEncoder encoder = new NfcPasswordEncoder(delegate);

    @Test
    void hashesCanonicalNfcAndAcceptsEquivalentUnicodeForms() {
        String encoded = encoder.encode(DECOMPOSED_PASSWORD);

        assertThat(encoder.matches(DECOMPOSED_PASSWORD, encoded)).isTrue();
        assertThat(encoder.matches(NFC_PASSWORD, encoded)).isTrue();
        assertThat(encoder.requiresUpgradeAfterSuccessfulMatch(DECOMPOSED_PASSWORD, encoded)).isFalse();
    }

    @Test
    void acceptsLegacyNonNfcHashAndMarksItForUpgrade() {
        String legacyHash = delegate.encode(DECOMPOSED_PASSWORD);

        assertThat(encoder.matches(DECOMPOSED_PASSWORD, legacyHash)).isTrue();
        assertThat(encoder.matches(NFC_PASSWORD, legacyHash)).isFalse();
        assertThat(encoder.requiresUpgradeAfterSuccessfulMatch(DECOMPOSED_PASSWORD, legacyHash)).isTrue();
    }

    @Test
    void retainsDelegateCostUpgradeSignal() {
        PasswordEncoder strongerDelegate = new DelegatingPasswordEncoder(
                "bcrypt",
                Map.of("bcrypt", new BCryptPasswordEncoder(5))
        );
        NfcPasswordEncoder strongerEncoder = new NfcPasswordEncoder(strongerDelegate);
        String weakHash = delegate.encode(NFC_PASSWORD);

        assertThat(strongerEncoder.requiresUpgradeAfterSuccessfulMatch(NFC_PASSWORD, weakHash)).isTrue();
    }
}
