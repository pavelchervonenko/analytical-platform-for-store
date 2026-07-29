package com.storeanalytics.auth.service;

import com.storeanalytics.auth.exception.PasswordPolicyViolationException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class PasswordPolicy {

    public static final int MINIMUM_LENGTH = 12;
    public static final int MAXIMUM_LENGTH = 128;
    public static final int BCRYPT_MAXIMUM_BYTES = 72;

    private static final Set<String> CONTEXT_SPECIFIC_PASSWORDS = Set.of(
            "administrator",
            "storeanalytics",
            "letmein123456",
            "changeme12345",
            "storeanalytics2026",
            "analyticalplatform",
            "analyticalplatformforstore"
    );

    private final CompromisedPasswordBlocklist compromisedPasswordBlocklist;

    public PasswordPolicy(CompromisedPasswordBlocklist compromisedPasswordBlocklist) {
        this.compromisedPasswordBlocklist = compromisedPasswordBlocklist;
    }

    public void validate(String password) {
        if (password == null) {
            throw new PasswordPolicyViolationException(
                    "Password must contain at least " + MINIMUM_LENGTH + " characters"
            );
        }
        String canonicalPassword = PasswordCanonicalizer.canonicalize(password);
        int codePointLength = canonicalPassword.codePointCount(0, canonicalPassword.length());
        if (codePointLength < MINIMUM_LENGTH) {
            throw new PasswordPolicyViolationException(
                    "Password must contain at least " + MINIMUM_LENGTH + " characters"
            );
        }
        if (codePointLength > MAXIMUM_LENGTH) {
            throw new PasswordPolicyViolationException(
                    "Password must contain no more than " + MAXIMUM_LENGTH + " characters"
            );
        }
        if (canonicalPassword.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAXIMUM_BYTES) {
            throw new PasswordPolicyViolationException(
                    "Password must contain no more than "
                            + BCRYPT_MAXIMUM_BYTES
                            + " UTF-8 bytes"
            );
        }
        if (canonicalPassword.codePoints().anyMatch(Character::isISOControl)) {
            throw new PasswordPolicyViolationException("Password must not contain control characters");
        }
        String contextLookupValue = canonicalPassword.toLowerCase(java.util.Locale.ROOT);
        if (CONTEXT_SPECIFIC_PASSWORDS.contains(contextLookupValue)
                || compromisedPasswordBlocklist.contains(canonicalPassword)) {
            throw new PasswordPolicyViolationException("Password is too common");
        }
    }
}
