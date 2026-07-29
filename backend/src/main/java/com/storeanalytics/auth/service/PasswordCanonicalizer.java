package com.storeanalytics.auth.service;

import java.text.Normalizer;
import java.util.Objects;

public final class PasswordCanonicalizer {

    private PasswordCanonicalizer() {
    }

    public static String canonicalize(CharSequence password) {
        return Normalizer.normalize(
                Objects.requireNonNull(password, "password").toString(),
                Normalizer.Form.NFC
        );
    }
}
