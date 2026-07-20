package com.storeanalytics.auth.service;

import com.storeanalytics.auth.exception.PasswordPolicyViolationException;
import org.springframework.stereotype.Component;

@Component
public class PasswordPolicy {

    public static final int MINIMUM_LENGTH = 8;
    public static final int MAXIMUM_LENGTH = 128;

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
    }
}
