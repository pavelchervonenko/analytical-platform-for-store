package com.storeanalytics.notification.linking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
public class TelegramLinkTokenGenerator {

    private static final int RANDOM_BYTES = 24;
    private final SecureRandom secureRandom = new SecureRandom();

    GeneratedTelegramLinkToken generate() {
        byte[] random = new byte[RANDOM_BYTES];
        secureRandom.nextBytes(random);
        String value = "v1_" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(random);
        return new GeneratedTelegramLinkToken(value, hash(value));
    }

    String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8)
            );
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
