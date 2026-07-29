package com.storeanalytics.auth.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
public final class OfflineCompromisedPasswordBlocklist implements CompromisedPasswordBlocklist {

    static final int EXPECTED_BLOCKED_DIGESTS = 46_146;
    static final String RESOURCE_PATH = "security/common-passwords.sha256";

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final HexFormat HEX = HexFormat.of();

    private final Set<String> blockedDigests;

    public OfflineCompromisedPasswordBlocklist() {
        this(new ClassPathResource(RESOURCE_PATH));
    }

    OfflineCompromisedPasswordBlocklist(Resource resource) {
        blockedDigests = load(resource);
    }

    @Override
    public boolean contains(String canonicalPassword) {
        String lookupValue = PasswordCanonicalizer.canonicalize(canonicalPassword)
                .toLowerCase(Locale.ROOT);
        return blockedDigests.contains(sha256(lookupValue));
    }

    int size() {
        return blockedDigests.size();
    }

    private static Set<String> load(Resource resource) {
        Set<String> digests = new HashSet<>();
        try (var reader = new BufferedReader(new InputStreamReader(
                resource.getInputStream(), StandardCharsets.US_ASCII
        ))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (!SHA_256.matcher(line).matches()) {
                    throw new IllegalStateException(
                            "Invalid password blocklist digest at line " + lineNumber
                    );
                }
                if (!digests.add(line)) {
                    throw new IllegalStateException(
                            "Duplicate password blocklist digest at line " + lineNumber
                    );
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Password blocklist cannot be loaded", exception);
        }
        if (digests.size() != EXPECTED_BLOCKED_DIGESTS) {
            throw new IllegalStateException(
                    "Password blocklist must contain " + EXPECTED_BLOCKED_DIGESTS + " digests"
            );
        }
        return Set.copyOf(digests);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
