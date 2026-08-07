package com.storeanalytics.notification.delivery;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class TelegramServiceMessageOutbox {

    static final String LINK_CONFIRMATION_TEXT =
            "✅ БОТ НАЙДЕН\n\nОстался один шаг: вернитесь в кабинет и подтвердите "
                    + "подключение. До подтверждения бизнес-уведомления не отправляются.";
    private static final String RENDER_VERSION = "telegram-link-confirmation-v2";

    private final JdbcTemplate jdbcTemplate;

    public TelegramServiceMessageOutbox(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int enqueueLinkConfirmation(
            UUID recipientUserId,
            UUID subscriptionId,
            Instant now,
            Instant expiresAt,
            int maxAttempts
    ) {
        return jdbcTemplate.update(
                """
                INSERT INTO notification_deliveries (
                    id, event_id, delivery_kind, channel, recipient_user_id,
                    subscription_id, status, render_version, rendered_text,
                    content_hash, scheduled_at, next_attempt_at, expires_at,
                    max_attempts, created_at, updated_at
                ) VALUES (
                    ?, NULL, 'LINK_CONFIRMATION', 'TELEGRAM', ?, ?, 'PENDING',
                    ?, ?, ?, ?, ?, ?, ?, ?, ?
                )
                ON CONFLICT (subscription_id, delivery_kind)
                    WHERE delivery_kind = 'LINK_CONFIRMATION'
                DO NOTHING
                """,
                UUID.randomUUID(),
                recipientUserId,
                subscriptionId,
                RENDER_VERSION,
                LINK_CONFIRMATION_TEXT,
                sha256(LINK_CONFIRMATION_TEXT),
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(expiresAt),
                maxAttempts,
                Timestamp.from(now),
                Timestamp.from(now)
        );
    }

    private String sha256(String value) {
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
