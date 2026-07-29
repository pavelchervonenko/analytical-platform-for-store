package com.storeanalytics.auth.security;

import com.storeanalytics.auth.service.PasswordCanonicalizer;
import java.util.Objects;
import org.springframework.security.crypto.password.PasswordEncoder;

public final class NfcPasswordEncoder implements PasswordEncoder {

    private final PasswordEncoder delegate;

    public NfcPasswordEncoder(PasswordEncoder delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public String encode(CharSequence rawPassword) {
        return delegate.encode(PasswordCanonicalizer.canonicalize(rawPassword));
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        String rawValue = Objects.requireNonNull(rawPassword, "rawPassword").toString();
        String canonicalValue = PasswordCanonicalizer.canonicalize(rawValue);
        if (delegate.matches(canonicalValue, encodedPassword)) {
            return true;
        }
        return !rawValue.equals(canonicalValue)
                && delegate.matches(rawValue, encodedPassword);
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        return delegate.upgradeEncoding(encodedPassword);
    }

    public boolean requiresUpgradeAfterSuccessfulMatch(CharSequence rawPassword, String encodedPassword) {
        if (delegate.upgradeEncoding(encodedPassword)) {
            return true;
        }
        String rawValue = Objects.requireNonNull(rawPassword, "rawPassword").toString();
        String canonicalValue = PasswordCanonicalizer.canonicalize(rawValue);
        return !rawValue.equals(canonicalValue)
                && !delegate.matches(canonicalValue, encodedPassword);
    }
}
