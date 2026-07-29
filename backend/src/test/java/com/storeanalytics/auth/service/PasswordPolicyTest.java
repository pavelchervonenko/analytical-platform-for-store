package com.storeanalytics.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.auth.exception.PasswordPolicyViolationException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    private final PasswordPolicy passwordPolicy = new PasswordPolicy(
            new OfflineCompromisedPasswordBlocklist()
    );

    @Test
    void keepsTwelveCharacterMinimumAndAcceptsPasswordManagerValuesAndPassphrases() {
        assertThat(PasswordPolicy.MINIMUM_LENGTH).isEqualTo(12);
        assertThatCode(() -> passwordPolicy.validate("X7!vQ2#nL9@p"))
                .doesNotThrowAnyException();
        assertThatCode(() -> passwordPolicy.validate("four calm words 2026"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsShortBlockedAndOversizedUtf8Passwords() {
        assertThatThrownBy(() -> passwordPolicy.validate("short"))
                .isInstanceOf(PasswordPolicyViolationException.class);
        assertThatThrownBy(() -> passwordPolicy.validate("PASSWORD1234"))
                .isInstanceOf(PasswordPolicyViolationException.class)
                .hasMessage("Password is too common");
        assertThatThrownBy(() -> passwordPolicy.validate("storeanalytics2026"))
                .isInstanceOf(PasswordPolicyViolationException.class);
        assertThatThrownBy(() -> passwordPolicy.validate("Я".repeat(40)))
                .isInstanceOf(PasswordPolicyViolationException.class)
                .hasMessageContaining("UTF-8 bytes");
    }

    @Test
    void normalizesUnicodeToNfcBeforePolicyChecks() {
        AtomicReference<String> checkedPassword = new AtomicReference<>();
        PasswordPolicy recordingPolicy = new PasswordPolicy(password -> {
            checkedPassword.set(password);
            return false;
        });

        recordingPolicy.validate("Cafe\u0301 phrase 2026");

        assertThat(checkedPassword).hasValue("Caf\u00e9 phrase 2026");
    }

    @Test
    void countsUnicodeCodePointsAndRejectsControlCharacters() {
        PasswordPolicy permissivePolicy = new PasswordPolicy(password -> false);

        assertThatCode(() -> permissivePolicy.validate("\uD83D\uDE00".repeat(12)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> permissivePolicy.validate("valid-password\n"))
                .isInstanceOf(PasswordPolicyViolationException.class)
                .hasMessageContaining("control characters");
    }
}
