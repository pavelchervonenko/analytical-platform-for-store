package com.storeanalytics.auth.service;

import com.storeanalytics.auth.exception.PasswordPolicyViolationException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class PasswordPolicy {

    public static final int MINIMUM_LENGTH = 12;
    public static final int MAXIMUM_LENGTH = 128;
    public static final int BCRYPT_MAXIMUM_BYTES = 72;

    private static final Set<String> COMMON_PASSWORDS = Set.of(
            "123456789012",
            "administrator",
            "qwerty123456",
            "password1234",
            "password12345",
            "letmein123456",
            "storeanalytics",
            "changeme12345"
    );

    public void validate(String password) {
        if (password == null || password.length() < MINIMUM_LENGTH) {
            throw new PasswordPolicyViolationException(
                    "Password must contain at least " + MINIMUM_LENGTH + " characters"
            );
        }
        if (password.length() > MAXIMUM_LENGTH) {
            throw new PasswordPolicyViolationException(
                    "Password must contain no more than " + MAXIMUM_LENGTH + " characters"
            );
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAXIMUM_BYTES) {
            throw new PasswordPolicyViolationException(
                    "Password must contain no more than "
                            + BCRYPT_MAXIMUM_BYTES
                            + " UTF-8 bytes"
            );
        }
        String normalized = password.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        if (COMMON_PASSWORDS.contains(normalized)) {
            throw new PasswordPolicyViolationException("Password is too common");
        }
        if (password.chars().anyMatch(Character::isISOControl)) {
            throw new PasswordPolicyViolationException("Password must not contain control characters");
        }
    }
}
