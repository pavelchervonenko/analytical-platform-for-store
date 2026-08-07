package com.storeanalytics.notification.operations;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TelegramDeliveryOperationsQuery {

    private final JdbcTemplate jdbcTemplate;

    public TelegramDeliveryOperationsQuery(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public TelegramDeliveryOperationsView get(int incidentLimit, Instant now) {
        TelegramDeliveryQueueSummary summary = jdbcTemplate.queryForObject(
                """
                SELECT
                    count(*) FILTER (
                        WHERE delivery.status = 'PENDING'
                          AND delivery.scheduled_at <= ?
                          AND delivery.next_attempt_at <= ?
                    ) AS ready_pending,
                    count(*) FILTER (
                        WHERE delivery.status = 'WAITING_RETRY'
                          AND delivery.scheduled_at <= ?
                          AND delivery.next_attempt_at <= ?
                    ) AS ready_retries,
                    count(*) FILTER (WHERE delivery.status = 'RUNNING') AS running,
                    count(*) FILTER (
                        WHERE delivery.status = 'RUNNING'
                          AND delivery.lease_until <= ?
                    ) AS overdue_running,
                    count(*) FILTER (
                        WHERE delivery.status = 'PERMANENT_FAILED'
                    ) AS permanent_failed,
                    count(*) FILTER (
                        WHERE delivery.status = 'UNKNOWN_OUTCOME'
                    ) AS unknown_outcome,
                    min(GREATEST(
                        delivery.next_attempt_at, delivery.scheduled_at
                    )) FILTER (
                        WHERE delivery.status IN ('PENDING', 'WAITING_RETRY')
                          AND delivery.scheduled_at <= ?
                          AND delivery.next_attempt_at <= ?
                    ) AS oldest_ready_at,
                    (SELECT count(*) FROM telegram_subscriptions
                     WHERE status = 'ACTIVE') AS active_subscriptions,
                    (SELECT count(*) FROM telegram_subscriptions
                     WHERE status = 'BOT_BLOCKED') AS blocked_subscriptions
                FROM notification_deliveries delivery
                """,
                this::mapSummary,
                Timestamp.from(now), Timestamp.from(now),
                Timestamp.from(now), Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now), Timestamp.from(now)
        );
        List<TelegramDeliveryIncidentView> incidents = jdbcTemplate.query(
                """
                SELECT delivery.id, delivery.delivery_kind, event.event_type,
                       store.name AS store_name, app_user.display_name AS recipient_name,
                       delivery.status, delivery.attempt_count, delivery.max_attempts,
                       delivery.next_attempt_at, delivery.lease_until, delivery.expires_at,
                       delivery.error_code, delivery.error_summary,
                       delivery.created_at, delivery.updated_at
                FROM notification_deliveries delivery
                JOIN app_users app_user ON app_user.id = delivery.recipient_user_id
                LEFT JOIN notification_events event ON event.id = delivery.event_id
                LEFT JOIN stores store ON store.id = event.store_id
                WHERE delivery.status IN ('PERMANENT_FAILED', 'UNKNOWN_OUTCOME')
                   OR (delivery.status = 'RUNNING' AND delivery.lease_until <= ?)
                   OR (delivery.status = 'WAITING_RETRY' AND delivery.error_code IS NOT NULL)
                ORDER BY CASE delivery.status
                    WHEN 'UNKNOWN_OUTCOME' THEN 0
                    WHEN 'RUNNING' THEN 1
                    WHEN 'PERMANENT_FAILED' THEN 2
                    ELSE 3
                END, delivery.updated_at DESC, delivery.id
                LIMIT ?
                """,
                this::mapIncident,
                Timestamp.from(now),
                incidentLimit
        );
        return new TelegramDeliveryOperationsView(now, summary, incidents);
    }

    private TelegramDeliveryQueueSummary mapSummary(ResultSet resultSet, int rowNumber)
            throws SQLException {
        long unknownOutcome = resultSet.getLong("unknown_outcome");
        long overdueRunning = resultSet.getLong("overdue_running");
        long permanentFailed = resultSet.getLong("permanent_failed");
        long readyPending = resultSet.getLong("ready_pending");
        long readyRetries = resultSet.getLong("ready_retries");
        long blockedSubscriptions = resultSet.getLong("blocked_subscriptions");
        TelegramDeliveryAttentionLevel attentionLevel;
        if (unknownOutcome > 0 || overdueRunning > 0) {
            attentionLevel = TelegramDeliveryAttentionLevel.CRITICAL;
        } else if (permanentFailed > 0 || readyPending > 0 || readyRetries > 0
                || blockedSubscriptions > 0) {
            attentionLevel = TelegramDeliveryAttentionLevel.WARNING;
        } else {
            attentionLevel = TelegramDeliveryAttentionLevel.NORMAL;
        }
        return new TelegramDeliveryQueueSummary(
                attentionLevel,
                readyPending,
                readyRetries,
                resultSet.getLong("running"),
                overdueRunning,
                permanentFailed,
                unknownOutcome,
                resultSet.getLong("active_subscriptions"),
                blockedSubscriptions,
                instant(resultSet, "oldest_ready_at")
        );
    }

    private TelegramDeliveryIncidentView mapIncident(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new TelegramDeliveryIncidentView(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("delivery_kind"),
                resultSet.getString("event_type"),
                resultSet.getString("store_name"),
                resultSet.getString("recipient_name"),
                resultSet.getString("status"),
                resultSet.getInt("attempt_count"),
                resultSet.getInt("max_attempts"),
                instant(resultSet, "next_attempt_at"),
                instant(resultSet, "lease_until"),
                instant(resultSet, "expires_at"),
                resultSet.getString("error_code"),
                resultSet.getString("error_summary"),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at")
        );
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
