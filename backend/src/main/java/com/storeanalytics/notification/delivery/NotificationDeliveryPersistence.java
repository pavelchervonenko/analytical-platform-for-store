package com.storeanalytics.notification.delivery;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class NotificationDeliveryPersistence {

    private final JdbcTemplate jdbcTemplate;

    public NotificationDeliveryPersistence(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public NotificationDeliveryRecoveryOutcome recoverOneExpiredLease(Instant now) {
        List<ExpiredLeaseRow> rows = jdbcTemplate.query(
                """
                SELECT delivery.id, delivery.cancel_requested, delivery.expires_at,
                       attempt.id AS attempt_id, attempt.status AS attempt_status
                FROM notification_deliveries delivery
                LEFT JOIN LATERAL (
                    SELECT candidate.id, candidate.status
                    FROM notification_delivery_attempts candidate
                    WHERE candidate.delivery_id = delivery.id
                    ORDER BY candidate.attempt_number DESC
                    LIMIT 1
                ) attempt ON true
                WHERE delivery.status = 'RUNNING' AND delivery.lease_until <= ?
                ORDER BY delivery.lease_until, delivery.id
                FOR UPDATE OF delivery SKIP LOCKED
                LIMIT 1
                """,
                this::mapExpiredLease,
                Timestamp.from(now)
        );
        if (rows.isEmpty()) {
            return NotificationDeliveryRecoveryOutcome.NONE;
        }
        ExpiredLeaseRow row = rows.getFirst();
        if ("STARTED".equals(row.attemptStatus())) {
            finishStartedAttemptAsUnknown(row.attemptId(), now);
            terminal(
                    row.deliveryId(),
                    "UNKNOWN_OUTCOME",
                    "LEASE_EXPIRED_AFTER_ATTEMPT",
                    "Delivery worker lease expired after provider attempt started",
                    now
            );
            return NotificationDeliveryRecoveryOutcome.UNKNOWN_OUTCOME;
        }
        if (row.cancelRequested()) {
            terminal(
                    row.deliveryId(),
                    "CANCELLED",
                    "CANCEL_REQUESTED",
                    "Delivery was cancelled before provider attempt",
                    now
            );
            return NotificationDeliveryRecoveryOutcome.CANCELLED;
        }
        if (!now.isBefore(row.expiresAt())) {
            terminal(
                    row.deliveryId(),
                    "EXPIRED",
                    "DELIVERY_EXPIRED",
                    "Delivery expired before provider attempt",
                    now
            );
            return NotificationDeliveryRecoveryOutcome.EXPIRED;
        }
        int updated = jdbcTemplate.update(
                """
                UPDATE notification_deliveries
                SET status = 'WAITING_RETRY', next_attempt_at = ?,
                    lease_owner = NULL, lease_until = NULL,
                    error_code = 'LEASE_RECOVERED',
                    error_summary = 'Delivery lease recovered before provider attempt',
                    version = version + 1
                WHERE id = ? AND status = 'RUNNING'
                """,
                Timestamp.from(now),
                row.deliveryId()
        );
        requireUpdated(updated, "expired delivery lease was changed concurrently");
        return NotificationDeliveryRecoveryOutcome.RETRY_RELEASED;
    }

    @Transactional
    public Optional<NotificationDeliveryClaim> claimNext(
            String owner,
            Instant now,
            Duration leaseDuration
    ) {
        List<UUID> ids = jdbcTemplate.query(
                """
                SELECT id
                FROM notification_deliveries
                WHERE status IN ('PENDING', 'WAITING_RETRY')
                  AND scheduled_at <= ? AND next_attempt_at <= ?
                ORDER BY next_attempt_at, scheduled_at, created_at, id
                FOR UPDATE SKIP LOCKED
                LIMIT 1
                """,
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                Timestamp.from(now),
                Timestamp.from(now)
        );
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        UUID id = ids.getFirst();
        int updated = jdbcTemplate.update(
                """
                UPDATE notification_deliveries
                SET status = 'RUNNING', lease_owner = ?, lease_until = ?,
                    error_code = NULL, error_summary = NULL,
                    version = version + 1
                WHERE id = ? AND status IN ('PENDING', 'WAITING_RETRY')
                """,
                owner,
                Timestamp.from(now.plus(leaseDuration)),
                id
        );
        requireUpdated(updated, "claimed delivery was changed concurrently");
        return Optional.of(new NotificationDeliveryClaim(id, owner));
    }

    @Transactional
    public Optional<PreparedTelegramDelivery> prepareAttempt(
            NotificationDeliveryClaim claim,
            Instant now
    ) {
        List<DeliveryRow> rows = jdbcTemplate.query(
                """
                SELECT delivery.id, delivery.subscription_id,
                       delivery.delivery_kind,
                       delivery.recipient_user_id, delivery.attempt_count,
                       delivery.max_attempts, delivery.rendered_text,
                       delivery.content_hash, delivery.expires_at,
                       delivery.cancel_requested, delivery.lease_owner,
                       delivery.lease_until,
                       subscription.user_id AS subscription_user_id,
                       subscription.telegram_chat_id, subscription.status AS subscription_status,
                       app_user.is_active, app_user.role,
                       event.audience, event.store_id,
                       CASE WHEN event.store_id IS NULL THEN true ELSE EXISTS (
                           SELECT 1 FROM user_store_access access
                           WHERE access.user_id = delivery.recipient_user_id
                             AND access.store_id = event.store_id
                       ) END AS has_store_access
                FROM notification_deliveries delivery
                LEFT JOIN notification_events event ON event.id = delivery.event_id
                JOIN telegram_subscriptions subscription
                  ON subscription.id = delivery.subscription_id
                JOIN app_users app_user ON app_user.id = delivery.recipient_user_id
                WHERE delivery.id = ? AND delivery.status = 'RUNNING'
                FOR UPDATE OF delivery
                """,
                this::mapDelivery,
                claim.deliveryId()
        );
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        DeliveryRow row = rows.getFirst();
        if (!claim.leaseOwner().equals(row.leaseOwner())
                || row.leaseUntil() == null || !row.leaseUntil().isAfter(now)) {
            return Optional.empty();
        }
        if (row.cancelRequested()) {
            terminal(row.id(), "CANCELLED", "CANCEL_REQUESTED",
                    "Delivery was cancelled before provider attempt", now);
            return Optional.empty();
        }
        if (!now.isBefore(row.expiresAt())) {
            terminal(row.id(), "EXPIRED", "DELIVERY_EXPIRED",
                    "Delivery expired before provider attempt", now);
            return Optional.empty();
        }
        if (!eligible(row)) {
            terminal(row.id(), "CANCELLED", "RECIPIENT_INELIGIBLE",
                    "Recipient is no longer eligible for this notification", now);
            return Optional.empty();
        }
        if (row.attemptCount() >= row.maxAttempts()) {
            terminal(row.id(), "PERMANENT_FAILED", "ATTEMPTS_EXHAUSTED",
                    "Delivery attempt limit is exhausted", now);
            return Optional.empty();
        }
        if (!sha256(row.renderedText()).equals(row.contentHash())) {
            terminal(row.id(), "PERMANENT_FAILED", "CONTENT_HASH_MISMATCH",
                    "Stored delivery content failed integrity verification", now);
            return Optional.empty();
        }
        int attemptNumber = row.attemptCount() + 1;
        UUID attemptId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO notification_delivery_attempts (
                    id, delivery_id, attempt_number, status, started_at, created_at
                ) VALUES (?, ?, ?, 'STARTED', ?, ?)
                """,
                attemptId,
                row.id(),
                attemptNumber,
                Timestamp.from(now),
                Timestamp.from(now)
        );
        int updated = jdbcTemplate.update(
                """
                UPDATE notification_deliveries
                SET attempt_count = ?, version = version + 1
                WHERE id = ? AND status = 'RUNNING' AND lease_owner = ?
                """,
                attemptNumber,
                row.id(),
                claim.leaseOwner()
        );
        requireUpdated(updated, "delivery attempt could not be started");
        return Optional.of(new PreparedTelegramDelivery(
                row.id(),
                attemptId,
                row.subscriptionId(),
                attemptNumber,
                row.maxAttempts(),
                row.telegramChatId(),
                row.renderedText(),
                row.contentHash(),
                row.expiresAt(),
                claim.leaseOwner()
        ));
    }

    @Transactional
    public void completeSuccess(
            PreparedTelegramDelivery delivery,
            TelegramSendReceipt receipt,
            Instant now
    ) {
        finishAttempt(
                delivery,
                new AttemptCompletion(
                        "SENT",
                        receipt.providerMessageId(),
                        receipt.httpStatus(),
                        null,
                        receipt.latencyMs(),
                        null,
                        null
                ),
                now
        );
        int updated = jdbcTemplate.update(
                """
                UPDATE notification_deliveries
                SET status = 'SENT', provider_message_id = ?, sent_at = ?,
                    lease_owner = NULL, lease_until = NULL,
                    error_code = NULL, error_summary = NULL,
                    version = version + 1
                WHERE id = ? AND status = 'RUNNING' AND lease_owner = ?
                """,
                receipt.providerMessageId(),
                Timestamp.from(now),
                delivery.deliveryId(),
                delivery.leaseOwner()
        );
        requireUpdated(updated, "successful delivery lease is no longer owned");
    }

    @Transactional
    public String completeFailure(
            PreparedTelegramDelivery delivery,
            TelegramSendException failure,
            Instant nextAttemptAt,
            long latencyMs,
            Instant now
    ) {
        String attemptStatus = switch (failure.getKind()) {
            case RATE_LIMITED, TRANSIENT_PROVIDER, AUTHENTICATION ->
                    "TRANSIENT_FAILED";
            case UNKNOWN_OUTCOME -> "UNKNOWN_OUTCOME";
            default -> "PERMANENT_FAILED";
        };
        finishAttempt(
                delivery,
                new AttemptCompletion(
                        attemptStatus,
                        null,
                        failure.getHttpStatus(),
                        failure.getRetryAfterAt(),
                        latencyMs,
                        failure.getKind().name(),
                        safeSummary(failure)
                ),
                now
        );

        return switch (failure.getKind()) {
            case RATE_LIMITED, TRANSIENT_PROVIDER, AUTHENTICATION ->
                    completeTransientFailure(delivery, failure, nextAttemptAt, now);
            case UNKNOWN_OUTCOME -> {
                terminal(delivery.deliveryId(), "UNKNOWN_OUTCOME",
                        failure.getKind().name(), safeSummary(failure), now);
                yield "UNKNOWN_OUTCOME";
            }
            case BOT_BLOCKED -> {
                terminal(delivery.deliveryId(), "PERMANENT_FAILED",
                        failure.getKind().name(), safeSummary(failure), now);
                blockSubscription(delivery.subscriptionId(), now);
                yield "BOT_BLOCKED";
            }
            case INVALID_REQUEST, PERMANENT_PROVIDER_REJECTED -> {
                terminal(delivery.deliveryId(), "PERMANENT_FAILED",
                        failure.getKind().name(), safeSummary(failure), now);
                yield "PERMANENT_FAILED";
            }
        };
    }

    private String completeTransientFailure(
            PreparedTelegramDelivery delivery,
            TelegramSendException failure,
            Instant nextAttemptAt,
            Instant now
    ) {
        if (delivery.attemptNumber() >= delivery.maxAttempts()) {
            terminal(delivery.deliveryId(), "PERMANENT_FAILED",
                    "ATTEMPTS_EXHAUSTED", "Delivery attempt limit is exhausted", now);
            return "ATTEMPTS_EXHAUSTED";
        }
        if (nextAttemptAt == null || !nextAttemptAt.isBefore(delivery.expiresAt())) {
            terminal(delivery.deliveryId(), "EXPIRED", "DELIVERY_EXPIRED",
                    "Delivery would expire before its next attempt", now);
            return "EXPIRED";
        }
        int updated = jdbcTemplate.update(
                """
                UPDATE notification_deliveries
                SET status = 'WAITING_RETRY', next_attempt_at = ?,
                    lease_owner = NULL, lease_until = NULL,
                    error_code = ?, error_summary = ?, version = version + 1
                WHERE id = ? AND status = 'RUNNING' AND lease_owner = ?
                """,
                Timestamp.from(nextAttemptAt),
                failure.getKind().name(),
                safeSummary(failure),
                delivery.deliveryId(),
                delivery.leaseOwner()
        );
        requireUpdated(updated, "retryable delivery lease is no longer owned");
        return "WAITING_RETRY";
    }

    private void finishAttempt(
            PreparedTelegramDelivery delivery,
            AttemptCompletion completion,
            Instant now
    ) {
        int updated = jdbcTemplate.update(
                """
                UPDATE notification_delivery_attempts
                SET status = ?, provider_message_id = ?, http_status = ?,
                    retry_after_at = ?, latency_ms = ?, error_code = ?,
                    error_summary = ?, finished_at = ?
                WHERE id = ? AND delivery_id = ? AND attempt_number = ?
                  AND status = 'STARTED'
                  AND EXISTS (
                      SELECT 1 FROM notification_deliveries delivery
                      WHERE delivery.id = notification_delivery_attempts.delivery_id
                        AND delivery.status = 'RUNNING' AND delivery.lease_owner = ?
                  )
                """,
                completion.status(),
                completion.providerMessageId(),
                completion.httpStatus(),
                timestamp(completion.retryAfterAt()),
                completion.latencyMs(),
                completion.errorCode(),
                completion.errorSummary(),
                Timestamp.from(now),
                delivery.attemptId(),
                delivery.deliveryId(),
                delivery.attemptNumber(),
                delivery.leaseOwner()
        );
        requireUpdated(updated, "delivery attempt is no longer owned");
    }

    private void finishStartedAttemptAsUnknown(UUID attemptId, Instant now) {
        if (attemptId == null) {
            return;
        }
        jdbcTemplate.update(
                """
                UPDATE notification_delivery_attempts
                SET status = 'UNKNOWN_OUTCOME',
                    error_code = 'LEASE_EXPIRED_AFTER_ATTEMPT',
                    error_summary = 'Worker lease expired after provider attempt started',
                    finished_at = ?
                WHERE id = ? AND status = 'STARTED'
                """,
                Timestamp.from(now),
                attemptId
        );
    }

    private void blockSubscription(UUID subscriptionId, Instant now) {
        jdbcTemplate.update(
                """
                UPDATE telegram_subscriptions
                SET status = 'BOT_BLOCKED', blocked_at = ?, version = version + 1
                WHERE id = ? AND status = 'ACTIVE'
                """,
                Timestamp.from(now),
                subscriptionId
        );
        jdbcTemplate.update(
                """
                UPDATE notification_deliveries
                SET status = 'CANCELLED', cancel_requested = true,
                    lease_owner = NULL, lease_until = NULL,
                    error_code = 'BOT_BLOCKED',
                    error_summary = 'Telegram bot is blocked for this destination',
                    version = version + 1
                WHERE subscription_id = ?
                  AND status IN ('PENDING', 'WAITING_RETRY')
                """,
                subscriptionId
        );
        jdbcTemplate.update(
                """
                UPDATE notification_deliveries
                SET cancel_requested = true, version = version + 1
                WHERE subscription_id = ? AND status = 'RUNNING'
                """,
                subscriptionId
        );
    }

    private void terminal(
            UUID deliveryId,
            String status,
            String errorCode,
            String errorSummary,
            Instant now
    ) {
        int updated = jdbcTemplate.update(
                """
                UPDATE notification_deliveries
                SET status = ?, lease_owner = NULL, lease_until = NULL,
                    error_code = ?, error_summary = ?, version = version + 1
                WHERE id = ? AND status = 'RUNNING'
                """,
                status,
                errorCode,
                errorSummary,
                deliveryId
        );
        requireUpdated(updated, "terminal delivery transition failed at " + now);
    }

    private boolean eligible(DeliveryRow row) {
        if (!row.active()
                || !row.recipientUserId().equals(row.subscriptionUserId())) {
            return false;
        }
        if ("LINK_CONFIRMATION".equals(row.deliveryKind())) {
            return "PENDING_CONFIRMATION".equals(row.subscriptionStatus());
        }
        if (!"NOTIFICATION".equals(row.deliveryKind())
                || !"ACTIVE".equals(row.subscriptionStatus())) {
            return false;
        }
        return switch (row.audience()) {
            case "MANAGER" -> "MANAGER".equals(row.role())
                    && row.storeId() != null && row.hasStoreAccess();
            case "OPERATOR" -> "ADMIN".equals(row.role());
            default -> false;
        };
    }

    private String safeSummary(TelegramSendException failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return "Telegram delivery failed";
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
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

    private ExpiredLeaseRow mapExpiredLease(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new ExpiredLeaseRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getBoolean("cancel_requested"),
                resultSet.getTimestamp("expires_at").toInstant(),
                resultSet.getObject("attempt_id", UUID.class),
                resultSet.getString("attempt_status")
        );
    }

    private DeliveryRow mapDelivery(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new DeliveryRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("subscription_id", UUID.class),
                resultSet.getString("delivery_kind"),
                resultSet.getObject("recipient_user_id", UUID.class),
                resultSet.getInt("attempt_count"),
                resultSet.getInt("max_attempts"),
                resultSet.getString("rendered_text"),
                resultSet.getString("content_hash"),
                resultSet.getTimestamp("expires_at").toInstant(),
                resultSet.getBoolean("cancel_requested"),
                resultSet.getString("lease_owner"),
                resultSet.getTimestamp("lease_until").toInstant(),
                resultSet.getObject("subscription_user_id", UUID.class),
                resultSet.getLong("telegram_chat_id"),
                resultSet.getString("subscription_status"),
                resultSet.getBoolean("is_active"),
                resultSet.getString("role"),
                resultSet.getString("audience"),
                resultSet.getObject("store_id", UUID.class),
                resultSet.getBoolean("has_store_access")
        );
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private void requireUpdated(int updated, String message) {
        if (updated != 1) {
            throw new IllegalStateException(message);
        }
    }

    private record AttemptCompletion(
            String status,
            String providerMessageId,
            Integer httpStatus,
            Instant retryAfterAt,
            long latencyMs,
            String errorCode,
            String errorSummary
    ) {
    }

    private record ExpiredLeaseRow(
            UUID deliveryId,
            boolean cancelRequested,
            Instant expiresAt,
            UUID attemptId,
            String attemptStatus
    ) {
    }

    private record DeliveryRow(
            UUID id,
            UUID subscriptionId,
            String deliveryKind,
            UUID recipientUserId,
            int attemptCount,
            int maxAttempts,
            String renderedText,
            String contentHash,
            Instant expiresAt,
            boolean cancelRequested,
            String leaseOwner,
            Instant leaseUntil,
            UUID subscriptionUserId,
            long telegramChatId,
            String subscriptionStatus,
            boolean active,
            String role,
            String audience,
            UUID storeId,
            boolean hasStoreAccess
    ) {
    }
}
