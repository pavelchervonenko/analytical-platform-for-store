package com.storeanalytics.notification.linking;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TelegramWebhookStore {

    private final JdbcTemplate jdbcTemplate;

    public TelegramWebhookStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int insertReceipt(
            String botCode,
            long updateId,
            String updateType,
            String payloadHash,
            Instant now
    ) {
        return jdbcTemplate.update(
                """
                INSERT INTO telegram_update_receipts (
                    bot_code, update_id, update_type, payload_hash,
                    outcome, processed_at, created_at
                ) VALUES (?, ?, ?, ?, 'IGNORED', ?, ?)
                ON CONFLICT (bot_code, update_id) DO NOTHING
                """,
                botCode,
                updateId,
                updateType,
                payloadHash,
                Timestamp.from(now),
                Timestamp.from(now)
        );
    }

    public void updateOutcome(
            String botCode,
            long updateId,
            TelegramWebhookOutcome outcome,
            Instant now
    ) {
        jdbcTemplate.update(
                """
                UPDATE telegram_update_receipts
                SET outcome = ?, processed_at = ?
                WHERE bot_code = ? AND update_id = ?
                """,
                outcome.name(),
                Timestamp.from(now),
                botCode,
                updateId
        );
    }

    public void lockDestination(long telegramUserId, long chatId) {
        long lockKey = telegramUserId ^ Long.rotateLeft(chatId, 17)
                ^ 0x54454C454752414DL;
        jdbcTemplate.queryForObject(
                "SELECT pg_advisory_xact_lock(?)",
                Object.class,
                lockKey
        );
    }
}
