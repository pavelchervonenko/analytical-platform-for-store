package com.storeanalytics.notification.linking;

import com.storeanalytics.notification.delivery.TelegramServiceMessageOutbox;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TelegramLinkStore {

    private final JdbcTemplate jdbcTemplate;
    private final TelegramServiceMessageOutbox serviceMessageOutbox;

    public TelegramLinkStore(
            JdbcTemplate jdbcTemplate,
            TelegramServiceMessageOutbox serviceMessageOutbox
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.serviceMessageOutbox = serviceMessageOutbox;
    }

    public void enqueueLinkConfirmation(
            UUID recipientUserId,
            UUID subscriptionId,
            Instant now,
            Instant expiresAt,
            int maxAttempts
    ) {
        serviceMessageOutbox.enqueueLinkConfirmation(
                recipientUserId, subscriptionId, now, expiresAt, maxAttempts
        );
    }

    public boolean lockActiveUser(UUID userId) {
        List<UUID> rows = jdbcTemplate.query(
                "SELECT id FROM app_users WHERE id = ? AND is_active = true FOR UPDATE",
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                userId
        );
        return !rows.isEmpty();
    }

    public Optional<TelegramSubscriptionRow> findCurrent(
            UUID userId,
            String botCode,
            Instant now
    ) {
        List<TelegramSubscriptionRow> rows = jdbcTemplate.query(
                """
                SELECT id, user_id, telegram_chat_id, status, delivery_timezone,
                       quiet_hours_enabled, quiet_hours_start, quiet_hours_end,
                       pending_expires_at, confirmed_at, blocked_at, created_at, version
                FROM telegram_subscriptions
                WHERE user_id = ?
                  AND bot_code = ?
                  AND (
                      status IN ('ACTIVE', 'BOT_BLOCKED')
                      OR (status = 'PENDING_CONFIRMATION' AND pending_expires_at > ?)
                  )
                ORDER BY CASE status
                    WHEN 'ACTIVE' THEN 1
                    WHEN 'BOT_BLOCKED' THEN 2
                    ELSE 3
                END, created_at DESC
                LIMIT 1
                """,
                this::mapSubscription,
                userId,
                botCode,
                Timestamp.from(now)
        );
        return rows.stream().findFirst();
    }

    public Optional<TelegramSubscriptionRow> lockCurrent(
            UUID userId,
            String botCode,
            Instant now
    ) {
        List<TelegramSubscriptionRow> rows = jdbcTemplate.query(
                """
                SELECT id, user_id, telegram_chat_id, status, delivery_timezone,
                       quiet_hours_enabled, quiet_hours_start, quiet_hours_end,
                       pending_expires_at, confirmed_at, blocked_at, created_at, version
                FROM telegram_subscriptions
                WHERE user_id = ?
                  AND bot_code = ?
                  AND (
                      status IN ('ACTIVE', 'BOT_BLOCKED')
                      OR (status = 'PENDING_CONFIRMATION' AND pending_expires_at > ?)
                  )
                ORDER BY created_at DESC
                FOR UPDATE
                LIMIT 1
                """,
                this::mapSubscription,
                userId,
                botCode,
                Timestamp.from(now)
        );
        return rows.stream().findFirst();
    }

    public Optional<Instant> findOpenLinkExpiry(
            UUID userId,
            String botCode,
            Instant now
    ) {
        List<Instant> rows = jdbcTemplate.query(
                """
                SELECT expires_at
                FROM telegram_link_tokens
                WHERE user_id = ? AND bot_code = ?
                  AND consumed_at IS NULL AND revoked_at IS NULL
                  AND expires_at > ?
                ORDER BY created_at DESC
                LIMIT 1
                """,
                (resultSet, rowNumber) -> resultSet.getTimestamp("expires_at")
                        .toInstant(),
                userId,
                botCode,
                Timestamp.from(now)
        );
        return rows.stream().findFirst();
    }

    public Optional<Instant> lastLinkCreatedAt(UUID userId, String botCode) {
        List<Instant> rows = jdbcTemplate.query(
                """
                SELECT created_at
                FROM telegram_link_tokens
                WHERE user_id = ? AND bot_code = ?
                ORDER BY created_at DESC
                LIMIT 1
                """,
                (resultSet, rowNumber) -> resultSet.getTimestamp("created_at")
                        .toInstant(),
                userId,
                botCode
        );
        return rows.stream().findFirst();
    }

    public int countLinksSince(UUID userId, String botCode, Instant since) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM telegram_link_tokens
                WHERE user_id = ? AND bot_code = ? AND created_at >= ?
                """,
                Integer.class,
                userId,
                botCode,
                Timestamp.from(since)
        );
        return count == null ? 0 : count;
    }

    public boolean hasSubscriptionHistory(UUID userId, String botCode) {
        Boolean exists = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1 FROM telegram_subscriptions
                    WHERE user_id = ? AND bot_code = ?
                )
                """,
                Boolean.class,
                userId,
                botCode
        );
        return Boolean.TRUE.equals(exists);
    }

    public void expirePendingAndCloseTokens(
            UUID userId,
            String botCode,
            Instant now
    ) {
        jdbcTemplate.update(
                """
                UPDATE telegram_subscriptions
                SET status = 'EXPIRED', version = version + 1
                WHERE user_id = ? AND bot_code = ?
                  AND status = 'PENDING_CONFIRMATION'
                  AND pending_expires_at <= ?
                """,
                userId,
                botCode,
                Timestamp.from(now)
        );
        jdbcTemplate.update(
                """
                UPDATE telegram_link_tokens
                SET revoked_at = ?
                WHERE user_id = ? AND bot_code = ?
                  AND consumed_at IS NULL AND revoked_at IS NULL
                """,
                Timestamp.from(now),
                userId,
                botCode
        );
    }

    public void insertLinkToken(
            UUID id,
            UUID userId,
            String botCode,
            String purpose,
            String tokenHash,
            Instant expiresAt,
            Instant now
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO telegram_link_tokens (
                    id, user_id, bot_code, purpose, token_hash, expires_at, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                userId,
                botCode,
                purpose,
                tokenHash,
                Timestamp.from(expiresAt),
                Timestamp.from(now)
        );
    }

    public Optional<TelegramLinkTokenRow> lockUsableToken(
            String botCode,
            String tokenHash,
            Instant now
    ) {
        List<TelegramLinkTokenRow> rows = jdbcTemplate.query(
                """
                SELECT id, user_id, expires_at
                FROM telegram_link_tokens
                WHERE bot_code = ? AND token_hash = ?
                  AND consumed_at IS NULL AND revoked_at IS NULL
                  AND expires_at > ?
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> new TelegramLinkTokenRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("user_id", UUID.class),
                        resultSet.getTimestamp("expires_at").toInstant()
                ),
                botCode,
                tokenHash,
                Timestamp.from(now)
        );
        return rows.stream().findFirst();
    }

    public void lockDestination(String botCode, long telegramUserId, long chatId) {
        jdbcTemplate.query(
                """
                SELECT id
                FROM telegram_subscriptions
                WHERE bot_code = ?
                  AND (telegram_user_id = ? OR telegram_chat_id = ?)
                  AND status IN ('PENDING_CONFIRMATION', 'ACTIVE', 'BOT_BLOCKED')
                FOR UPDATE
                """,
                (org.springframework.jdbc.core.RowCallbackHandler) resultSet -> {
                    // The row lock is the result; identifiers are intentionally not copied.
                },
                botCode,
                telegramUserId,
                chatId
        );
    }

    public boolean destinationBelongsToAnotherUser(
            String botCode,
            long telegramUserId,
            long chatId,
            UUID userId
    ) {
        Boolean exists = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM telegram_subscriptions
                    WHERE bot_code = ?
                      AND (telegram_user_id = ? OR telegram_chat_id = ?)
                      AND status IN ('PENDING_CONFIRMATION', 'ACTIVE', 'BOT_BLOCKED')
                      AND user_id <> ?
                )
                """,
                Boolean.class,
                botCode,
                telegramUserId,
                chatId,
                userId
        );
        return Boolean.TRUE.equals(exists);
    }

    public UUID insertPendingSubscription(
            UUID userId,
            String botCode,
            long telegramUserId,
            long chatId,
            Instant expiresAt,
            Instant now
    ) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO telegram_subscriptions (
                    id, user_id, bot_code, telegram_user_id, telegram_chat_id,
                    status, pending_expires_at, last_inbound_at, created_at, updated_at
                ) VALUES (
                    ?, ?, ?, ?, ?, 'PENDING_CONFIRMATION', ?, ?, ?, ?
                )
                """,
                id,
                userId,
                botCode,
                telegramUserId,
                chatId,
                Timestamp.from(expiresAt),
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now)
        );
        return id;
    }

    public void consumeToken(
            UUID tokenId,
            UUID subscriptionId,
            Instant now
    ) {
        jdbcTemplate.update(
                """
                UPDATE telegram_link_tokens
                SET consumed_at = ?, pending_subscription_id = ?
                WHERE id = ? AND consumed_at IS NULL AND revoked_at IS NULL
                """,
                Timestamp.from(now),
                subscriptionId,
                tokenId
        );
    }

    public void confirm(UUID subscriptionId, Instant now) {
        int updated = jdbcTemplate.update(
                """
                UPDATE telegram_subscriptions
                SET status = 'ACTIVE', pending_expires_at = NULL,
                    confirmed_at = ?, version = version + 1
                WHERE id = ? AND status = 'PENDING_CONFIRMATION'
                """,
                Timestamp.from(now),
                subscriptionId
        );
        if (updated == 1) {
            cancelLinkConfirmationDelivery(subscriptionId);
        }
    }

    private void cancelLinkConfirmationDelivery(UUID subscriptionId) {
        jdbcTemplate.update(
                """
                UPDATE notification_deliveries
                SET status = 'CANCELLED', cancel_requested = true,
                    lease_owner = NULL, lease_until = NULL,
                    error_code = 'LINK_CONFIRMED',
                    error_summary = 'Dashboard link confirmation was completed',
                    version = version + 1
                WHERE subscription_id = ?
                  AND delivery_kind = 'LINK_CONFIRMATION'
                  AND status IN ('PENDING', 'WAITING_RETRY')
                """,
                subscriptionId
        );
        jdbcTemplate.update(
                """
                UPDATE notification_deliveries
                SET cancel_requested = true, version = version + 1
                WHERE subscription_id = ?
                  AND delivery_kind = 'LINK_CONFIRMATION'
                  AND status = 'RUNNING'
                """,
                subscriptionId
        );
    }

    public void updateDeliverySettings(
            UUID subscriptionId,
            String timezone,
            boolean quietHoursEnabled,
            LocalTime quietHoursStart,
            LocalTime quietHoursEnd
    ) {
        int updated = jdbcTemplate.update(
                """
                UPDATE telegram_subscriptions
                SET delivery_timezone = ?, quiet_hours_enabled = ?,
                    quiet_hours_start = ?, quiet_hours_end = ?,
                    version = version + 1
                WHERE id = ? AND status = 'ACTIVE'
                """,
                timezone,
                quietHoursEnabled,
                quietHoursStart,
                quietHoursEnd,
                subscriptionId
        );
        if (updated != 1) {
            throw new TelegramLinkStateConflictException(
                    "Active Telegram subscription changed during settings update"
            );
        }
    }

    public void revoke(UUID subscriptionId, UUID userId, String botCode, Instant now) {
        jdbcTemplate.update(
                """
                UPDATE telegram_subscriptions
                SET status = 'REVOKED', revoked_at = ?, version = version + 1
                WHERE id = ? AND user_id = ? AND bot_code = ?
                  AND status IN ('PENDING_CONFIRMATION', 'ACTIVE', 'BOT_BLOCKED')
                """,
                Timestamp.from(now),
                subscriptionId,
                userId,
                botCode
        );
        jdbcTemplate.update(
                """
                UPDATE notification_deliveries
                SET status = 'CANCELLED', cancel_requested = true,
                    lease_owner = NULL, lease_until = NULL, version = version + 1
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
        jdbcTemplate.update(
                """
                UPDATE telegram_link_tokens
                SET revoked_at = ?
                WHERE user_id = ? AND bot_code = ?
                  AND consumed_at IS NULL AND revoked_at IS NULL
                """,
                Timestamp.from(now),
                userId,
                botCode
        );
    }

    Optional<TelegramMembershipTransition> applyMembershipUpdate(
            String botCode,
            long chatId,
            long updateId,
            String newMembershipStatus,
            Instant now
    ) {
        List<MembershipTarget> rows = jdbcTemplate.query(
                """
                SELECT id, status, confirmed_at,
                       last_membership_update_id, last_membership_update_at
                FROM telegram_subscriptions
                WHERE bot_code = ? AND telegram_chat_id = ?
                  AND status IN (
                      'PENDING_CONFIRMATION', 'ACTIVE', 'BOT_BLOCKED'
                  )
                FOR UPDATE
                """,
                this::mapMembershipTarget,
                botCode,
                chatId
        );
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        MembershipTarget target = rows.getFirst();
        if (target.lastUpdateId() != null
                && updateId <= target.lastUpdateId()
                && now.isBefore(target.lastUpdateAt().plus(
                        7,
                        ChronoUnit.DAYS
                ))) {
            return Optional.of(new TelegramMembershipTransition(
                    target.id(),
                    target.status(),
                    target.status(),
                    true
            ));
        }

        String nextStatus = membershipStatus(target, newMembershipStatus);
        if (target.status().equals(nextStatus)) {
            updateMembershipCursor(target.id(), updateId, now);
        } else {
            transitionMembership(target.id(), nextStatus, updateId, now);
            if ("BOT_BLOCKED".equals(nextStatus)
                    || "EXPIRED".equals(nextStatus)) {
                cancelDeliveriesForUnavailableSubscription(target.id());
            }
        }
        return Optional.of(new TelegramMembershipTransition(
                target.id(),
                target.status(),
                nextStatus,
                false
        ));
    }

    private String membershipStatus(
            MembershipTarget target,
            String telegramStatus
    ) {
        if (("kicked".equals(telegramStatus) || "left".equals(telegramStatus))
                && "ACTIVE".equals(target.status())) {
            return "BOT_BLOCKED";
        }
        if (("kicked".equals(telegramStatus) || "left".equals(telegramStatus))
                && "PENDING_CONFIRMATION".equals(target.status())) {
            return "EXPIRED";
        }
        if ("member".equals(telegramStatus)
                && "BOT_BLOCKED".equals(target.status())
                && target.confirmedAt() != null) {
            return "ACTIVE";
        }
        return target.status();
    }

    private void updateMembershipCursor(
            UUID subscriptionId,
            long updateId,
            Instant now
    ) {
        jdbcTemplate.update(
                """
                UPDATE telegram_subscriptions
                SET last_membership_update_id = ?,
                    last_membership_update_at = ?, last_inbound_at = ?
                WHERE id = ?
                """,
                updateId,
                Timestamp.from(now),
                Timestamp.from(now),
                subscriptionId
        );
    }

    private void transitionMembership(
            UUID subscriptionId,
            String status,
            long updateId,
            Instant now
    ) {
        jdbcTemplate.update(
                """
                UPDATE telegram_subscriptions
                SET status = ?,
                    blocked_at = CASE
                        WHEN ? = 'BOT_BLOCKED' THEN ?
                        WHEN ? = 'ACTIVE' THEN NULL
                        ELSE blocked_at
                    END,
                    pending_expires_at = CASE
                        WHEN ? = 'EXPIRED' THEN NULL
                        ELSE pending_expires_at
                    END,
                    last_membership_update_id = ?,
                    last_membership_update_at = ?, last_inbound_at = ?,
                    version = version + 1
                WHERE id = ?
                """,
                status,
                status,
                Timestamp.from(now),
                status,
                status,
                updateId,
                Timestamp.from(now),
                Timestamp.from(now),
                subscriptionId
        );
    }

    private void cancelDeliveriesForUnavailableSubscription(
            UUID subscriptionId
    ) {
        jdbcTemplate.update(
                """
                UPDATE notification_deliveries
                SET status = 'CANCELLED', cancel_requested = true,
                    lease_owner = NULL, lease_until = NULL,
                    error_code = 'BOT_UNAVAILABLE',
                    error_summary = 'Telegram bot is unavailable for this destination',
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

    private MembershipTarget mapMembershipTarget(
            ResultSet resultSet,
            int rowNumbe
    ) throws SQLException {
        Long lastUpdateId = resultSet.getObject(
                "last_membership_update_id",
                Long.class
        );
        return new MembershipTarget(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("status"),
                instant(resultSet, "confirmed_at"),
                lastUpdateId,
                instant(resultSet, "last_membership_update_at")
        );
    }

    private TelegramSubscriptionRow mapSubscription(
            ResultSet resultSet,
            int rowNumbe
    ) throws SQLException {
        return new TelegramSubscriptionRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("user_id", UUID.class),
                resultSet.getLong("telegram_chat_id"),
                resultSet.getString("status"),
                resultSet.getString("delivery_timezone"),
                resultSet.getBoolean("quiet_hours_enabled"),
                resultSet.getObject("quiet_hours_start", java.time.LocalTime.class),
                resultSet.getObject("quiet_hours_end", java.time.LocalTime.class),
                instant(resultSet, "pending_expires_at"),
                instant(resultSet, "confirmed_at"),
                instant(resultSet, "blocked_at"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getLong("version")
        );
    }

    private record MembershipTarget(
            UUID id,
            String status,
            Instant confirmedAt,
            Long lastUpdateId,
            Instant lastUpdateAt
    ) {
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
