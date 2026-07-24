package com.storeanalytics.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SecurityAuditLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger("SECURITY_AUDIT");

    public void loginFailed(String email, String clientAddress) {
        LOGGER.warn(
                "event=login_failed email_ref={} client_ref={}",
                pseudonym(normalizeEmail(email)),
                pseudonym(clientAddress)
        );
    }

    public void loginSucceeded(UUID userId, String clientAddress) {
        LOGGER.info(
                "event=login_succeeded user_id={} client_ref={}",
                userId,
                pseudonym(clientAddress)
        );
    }

    public void passwordChanged(UUID userId) {
        LOGGER.info("event=password_changed user_id={}", userId);
    }

    public void sessionRejected(UUID userId, String reason) {
        LOGGER.warn("event=session_rejected user_id={} reason={}", userId, reason);
    }

    public void userAdministration(String action, UUID actorId, UUID subjectId) {
        LOGGER.info(
                "event=user_administration action={} actor_id={} subject_id={}",
                action,
                actorId,
                subjectId
        );
    }

    public static String pseudonym(String value) {
        String safeValue = value == null ? "" : value;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(safeValue.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
