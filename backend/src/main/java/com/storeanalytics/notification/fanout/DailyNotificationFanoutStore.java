package com.storeanalytics.notification.fanout;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DailyNotificationFanoutStore {

    private final JdbcTemplate jdbcTemplate;

    public DailyNotificationFanoutStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<DailyNotificationEvent> claimNext(Instant now) {
        List<DailyNotificationEvent> rows = jdbcTemplate.query(
                """
                SELECT event.id, event.store_id, store.name AS store_name,
                       event.event_type, event.event_payload::text,
                       event.payload_hash, event.not_before, event.expires_at
                FROM notification_events event
                JOIN stores store ON store.id = event.store_id
                WHERE event.event_type = 'DAILY_STORE_PULSE'
                  AND event.not_before <= ?
                  AND event.expires_at IS NOT NULL
                  AND NOT EXISTS (
                      SELECT 1 FROM notification_event_fanout_receipts receipt
                      WHERE receipt.event_id = event.id
                  )
                ORDER BY event.not_before, event.created_at, event.id
                FOR UPDATE OF event SKIP LOCKED
                LIMIT 1
                """,
                this::mapEvent,
                Timestamp.from(now)
        );
        return rows.stream().findFirst();
    }

    public List<TelegramNotificationRecipient> recipients(
            UUID storeId,
            String eventType,
            String botCode
    ) {
        return jdbcTemplate.query(
                """
                SELECT subscription.user_id, subscription.id AS subscription_id,
                       subscription.delivery_timezone,
                       subscription.quiet_hours_enabled,
                       subscription.quiet_hours_start,
                       subscription.quiet_hours_end
                FROM telegram_subscriptions subscription
                JOIN app_users app_user ON app_user.id = subscription.user_id
                JOIN user_store_access access
                  ON access.user_id = app_user.id AND access.store_id = ?
                LEFT JOIN notification_preferences preference
                  ON preference.user_id = app_user.id
                 AND preference.store_id = access.store_id
                 AND preference.channel = 'TELEGRAM'
                 AND preference.event_type = ?
                WHERE subscription.bot_code = ?
                  AND subscription.status = 'ACTIVE'
                  AND app_user.is_active = true
                  AND app_user.role = 'MANAGER'
                  AND COALESCE(preference.enabled, true) = true
                ORDER BY subscription.id
                """,
                this::mapRecipient,
                storeId,
                eventType,
                botCode
        );
    }

    public int insertDelivery(
            DailyNotificationEvent event,
            TelegramNotificationRecipient recipient,
            RenderedTelegramMessage message,
            TelegramDeliverySchedule schedule,
            String renderVersion,
            int maxAttempts,
            Instant now
    ) {
        String status = schedule.expired() ? "EXPIRED" : "PENDING";
        return jdbcTemplate.update(
                """
                INSERT INTO notification_deliveries (
                    event_id, channel, recipient_user_id, subscription_id,
                    status, render_version, rendered_text, content_hash,
                    scheduled_at, next_attempt_at, expires_at,
                    max_attempts, created_at, updated_at
                ) VALUES (
                    ?, 'TELEGRAM', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                )
                ON CONFLICT (event_id, channel, subscription_id)
                WHERE manual_resend_of IS NULL AND event_id IS NOT NULL
                DO NOTHING
                """,
                event.id(),
                recipient.userId(),
                recipient.subscriptionId(),
                status,
                renderVersion,
                message.text(),
                message.contentHash(),
                Timestamp.from(schedule.scheduledAt()),
                Timestamp.from(schedule.scheduledAt()),
                Timestamp.from(event.expiresAt()),
                maxAttempts,
                Timestamp.from(now),
                Timestamp.from(now)
        );
    }

    public void insertReceipt(NotificationFanoutResult result, Instant now) {
        jdbcTemplate.update(
                """
                INSERT INTO notification_event_fanout_receipts (
                    event_id, outcome, recipient_count, delivery_count, processed_at
                ) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """,
                result.eventId(),
                result.outcome().name(),
                result.recipientCount(),
                result.deliveryCount(),
                Timestamp.from(now)
        );
    }

    private DailyNotificationEvent mapEvent(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new DailyNotificationEvent(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("store_id", UUID.class),
                resultSet.getString("store_name"),
                resultSet.getString("event_type"),
                resultSet.getString("event_payload"),
                resultSet.getString("payload_hash"),
                resultSet.getTimestamp("not_before").toInstant(),
                resultSet.getTimestamp("expires_at").toInstant()
        );
    }

    private TelegramNotificationRecipient mapRecipient(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        return new TelegramNotificationRecipient(
                resultSet.getObject("user_id", UUID.class),
                resultSet.getObject("subscription_id", UUID.class),
                ZoneId.of(resultSet.getString("delivery_timezone")),
                resultSet.getBoolean("quiet_hours_enabled"),
                resultSet.getObject("quiet_hours_start", java.time.LocalTime.class),
                resultSet.getObject("quiet_hours_end", java.time.LocalTime.class)
        );
    }
}
