package com.storeanalytics.notification.delivery;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TelegramDeliveryOperationalStateStore {

    private final JdbcTemplate jdbcTemplate;

    public TelegramDeliveryOperationalStateStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public TelegramDeliveryOperationalState load(Instant now) {
        return jdbcTemplate.queryForObject(
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
                    ) AS ready_retry,
                    count(*) FILTER (
                        WHERE delivery.status = 'WAITING_RETRY'
                          AND delivery.error_code = 'AUTHENTICATION'
                    ) AS authentication_retry,
                    count(*) FILTER (
                        WHERE delivery.status = 'RUNNING'
                    ) AS running,
                    count(*) FILTER (
                        WHERE delivery.status = 'RUNNING'
                          AND delivery.lease_until <= ?
                    ) AS expired_lease,
                    count(*) FILTER (
                        WHERE delivery.status = 'PERMANENT_FAILED'
                    ) AS permanent_failed,
                    count(*) FILTER (
                        WHERE delivery.status = 'UNKNOWN_OUTCOME'
                    ) AS unknown_outcome,
                    (SELECT count(*) FROM telegram_subscriptions
                     WHERE status = 'BOT_BLOCKED') AS blocked_subscription
                FROM notification_deliveries delivery
                """,
                this::map,
                Timestamp.from(now), Timestamp.from(now),
                Timestamp.from(now), Timestamp.from(now),
                Timestamp.from(now)
        );
    }

    private TelegramDeliveryOperationalState map(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new TelegramDeliveryOperationalState(
                resultSet.getLong("ready_pending"),
                resultSet.getLong("ready_retry"),
                resultSet.getLong("authentication_retry"),
                resultSet.getLong("running"),
                resultSet.getLong("expired_lease"),
                resultSet.getLong("permanent_failed"),
                resultSet.getLong("unknown_outcome"),
                resultSet.getLong("blocked_subscription")
        );
    }
}
