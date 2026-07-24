package com.storeanalytics.auth.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.auth.exception.PasswordPolicyViolationException;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    private final PasswordPolicy passwordPolicy = new PasswordPolicy();

    @Test
    void acceptsLongPassphraseWithinBcryptByteLimit() {
        assertThatCode(() -> passwordPolicy.validate("four calm words 2026"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsShortCommonAndOversizedUtf8Passwords() {
        assertThatThrownBy(() -> passwordPolicy.validate("short"))
                .isInstanceOf(PasswordPolicyViolationException.class);
        assertThatThrownBy(() -> passwordPolicy.validate("password1234"))
                .isInstanceOf(PasswordPolicyViolationException.class);
        assertThatThrownBy(() -> passwordPolicy.validate("Я".repeat(40)))
                .isInstanceOf(PasswordPolicyViolationException.class)
                .hasMessageContaining("UTF-8 bytes");
    }
}
