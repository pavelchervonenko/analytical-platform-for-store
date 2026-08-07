package com.storeanalytics.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class NotificationDeliveryPersistenceIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private NotificationDeliveryPersistence persistence;

    @Autowired
    private TelegramServiceMessageOutbox serviceMessageOutbox;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Test
    void sendsPendingLinkConfirmationWithoutBusinessEvent() {
        Instant now = Instant.now();
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO app_users (id, email, password_hash, display_name, role) "
                        + "VALUES (?, ?, '{noop}test', 'Manager', 'MANAGER')",
                userId,
                "service-delivery-" + userId + "@example.test"
        );
        UUID subscriptionId = UUID.randomUUID();
        long chatId = positive(userId.getLeastSignificantBits());
        jdbcTemplate.update(
                """
                INSERT INTO telegram_subscriptions (
                    id, user_id, bot_code, telegram_user_id, telegram_chat_id,
                    status, pending_expires_at
                ) VALUES (?, ?, 'primary', ?, ?, 'PENDING_CONFIRMATION', ?)
                """,
                subscriptionId,
                userId,
                positive(userId.getMostSignificantBits()),
                chatId,
                Timestamp.from(now.plus(Duration.ofMinutes(10)))
        );

        assertThat(serviceMessageOutbox.enqueueLinkConfirmation(
                userId,
                subscriptionId,
                now,
                now.plus(Duration.ofMinutes(10)),
                5
        )).isOne();
        assertThat(serviceMessageOutbox.enqueueLinkConfirmation(
                userId,
                subscriptionId,
                now,
                now.plus(Duration.ofMinutes(10)),
                5
        )).isZero();
        UUID deliveryId = jdbcTemplate.queryForObject(
                "SELECT id FROM notification_deliveries WHERE subscription_id = ?",
                UUID.class,
                subscriptionId
        );

        NotificationDeliveryClaim claim = persistence.claimNext(
                "service-test-worker",
                now,
                Duration.ofMinutes(1)
        ).orElseThrow();
        assertThat(claim.deliveryId()).isEqualTo(deliveryId);
        PreparedTelegramDelivery delivery = persistence.prepareAttempt(claim, now)
                .orElseThrow();

        assertThat(delivery.chatId()).isEqualTo(chatId);
        assertThat(delivery.text()).contains("подтвердите подключение");
        persistence.completeSuccess(
                delivery,
                new TelegramSendReceipt("service-123", 200, 10),
                now.plusMillis(10)
        );
        assertThat(status(deliveryId)).isEqualTo("SENT");
    }


    @Test
    void commitsAttemptBeforeSuccessAndStoresProviderMessageId() {
        Fixture fixture = fixture();
        Instant now = Instant.now();
        PreparedTelegramDelivery delivery = claimAndPrepare(fixture, now);

        persistence.completeSuccess(
                delivery,
                new TelegramSendReceipt("12345", 200, 42),
                now.plusMillis(42)
        );

        assertThat(status(fixture.deliveryId())).isEqualTo("SENT");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM notification_delivery_attempts WHERE delivery_id = ?",
                String.class,
                fixture.deliveryId()
        )).isEqualTo("SENT");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT provider_message_id FROM notification_deliveries WHERE id = ?",
                String.class,
                fixture.deliveryId()
        )).isEqualTo("12345");
    }

    @Test
    void explicitRateLimitSchedulesBoundedRetry() {
        Fixture fixture = fixture();
        Instant now = Instant.now();
        PreparedTelegramDelivery delivery = claimAndPrepare(fixture, now);
        Instant retryAt = now.plusSeconds(60);

        String outcome = persistence.completeFailure(
                delivery,
                failure(TelegramSendFailureKind.RATE_LIMITED, 429, retryAt),
                retryAt,
                20,
                now.plusMillis(20)
        );

        assertThat(outcome).isEqualTo("WAITING_RETRY");
        assertThat(status(fixture.deliveryId())).isEqualTo("WAITING_RETRY");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM notification_delivery_attempts WHERE delivery_id = ?",
                String.class,
                fixture.deliveryId()
        )).isEqualTo("TRANSIENT_FAILED");
    }

    @Test
    void expiredLeaseAfterStartedAttemptBecomesUnknownWithoutRetry() {
        Fixture fixture = fixture();
        Instant now = Instant.now();
        claimAndPrepare(fixture, now);
        jdbcTemplate.update(
                "UPDATE notification_deliveries SET lease_until = ? WHERE id = ?",
                Timestamp.from(now.minusSeconds(1)),
                fixture.deliveryId()
        );

        NotificationDeliveryRecoveryOutcome outcome =
                persistence.recoverOneExpiredLease(now);

        assertThat(outcome).isEqualTo(
                NotificationDeliveryRecoveryOutcome.UNKNOWN_OUTCOME
        );
        assertThat(status(fixture.deliveryId())).isEqualTo("UNKNOWN_OUTCOME");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM notification_delivery_attempts WHERE delivery_id = ?",
                String.class,
                fixture.deliveryId()
        )).isEqualTo("UNKNOWN_OUTCOME");
    }

    @Test
    void revokedStoreAccessCancelsBeforeAttempt() {
        Fixture fixture = fixture();
        Instant now = Instant.now();
        NotificationDeliveryClaim claim = persistence.claimNext(
                "test-worker",
                now,
                Duration.ofMinutes(1)
        ).orElseThrow();
        jdbcTemplate.update(
                "DELETE FROM user_store_access WHERE user_id = ? AND store_id = ?",
                fixture.userId(),
                fixture.storeId()
        );

        assertThat(persistence.prepareAttempt(claim, now)).isEmpty();
        assertThat(status(fixture.deliveryId())).isEqualTo("CANCELLED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_delivery_attempts WHERE delivery_id = ?",
                Integer.class,
                fixture.deliveryId()
        )).isZero();
    }

    @Test
    void explicitForbiddenResponseBlocksSubscription() {
        Fixture fixture = fixture();
        Instant now = Instant.now();
        PreparedTelegramDelivery delivery = claimAndPrepare(fixture, now);

        String outcome = persistence.completeFailure(
                delivery,
                failure(TelegramSendFailureKind.BOT_BLOCKED, 403, null),
                null,
                10,
                now.plusMillis(10)
        );

        assertThat(outcome).isEqualTo("BOT_BLOCKED");
        assertThat(status(fixture.deliveryId())).isEqualTo("PERMANENT_FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM telegram_subscriptions WHERE id = ?",
                String.class,
                fixture.subscriptionId()
        )).isEqualTo("BOT_BLOCKED");
    }

    private PreparedTelegramDelivery claimAndPrepare(Fixture fixture, Instant now) {
        NotificationDeliveryClaim claim = persistence.claimNext(
                "test-worker",
                now,
                Duration.ofMinutes(1)
        ).orElseThrow();
        assertThat(claim.deliveryId()).isEqualTo(fixture.deliveryId());
        return persistence.prepareAttempt(claim, now).orElseThrow();
    }

    private Fixture fixture() {
        Instant now = Instant.now();
        UUID connectionId = jdbcTemplate.queryForObject(
                "SELECT id FROM integration_connections "
                        + "WHERE connection_key = 'livesklad-default'",
                UUID.class
        );
        UUID storeId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO stores (id, connection_id, source_system, external_id, "
                        + "name) VALUES (?, ?, 'LIVESKLAD', ?, 'Delivery store')",
                storeId,
                connectionId,
                "delivery-" + storeId
        );
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO app_users (id, email, password_hash, display_name, role) "
                        + "VALUES (?, ?, '{noop}test', 'Manager', 'MANAGER')",
                userId,
                "delivery-" + userId + "@example.test"
        );
        jdbcTemplate.update(
                "INSERT INTO user_store_access (user_id, store_id) VALUES (?, ?)",
                userId,
                storeId
        );
        UUID subscriptionId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO telegram_subscriptions (
                    id, user_id, bot_code, telegram_user_id, telegram_chat_id,
                    status, quiet_hours_enabled, confirmed_at
                ) VALUES (?, ?, 'primary', ?, ?, 'ACTIVE', false, ?)
                """,
                subscriptionId,
                userId,
                positive(userId.getMostSignificantBits()),
                positive(userId.getLeastSignificantBits()),
                Timestamp.from(now)
        );
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO notification_events (
                    id, store_id, event_type, audience, deduplication_key,
                    notification_policy_version, priority, event_payload,
                    payload_hash, not_before, expires_at
                ) VALUES (
                    ?, ?, 'DAILY_STORE_PULSE', 'MANAGER', ?, 'test-v1',
                    'NORMAL', '{}'::jsonb, ?, ?, ?
                )
                """,
                eventId,
                storeId,
                "delivery-test:" + eventId,
                "1".repeat(64),
                Timestamp.from(now.minusSeconds(1)),
                Timestamp.from(now.plusSeconds(3600))
        );
        String text = "Безопасная сводка";
        UUID deliveryId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO notification_deliveries (
                    id, event_id, recipient_user_id, subscription_id, status,
                    render_version, rendered_text, content_hash,
                    scheduled_at, next_attempt_at, expires_at, max_attempts
                ) VALUES (
                    ?, ?, ?, ?, 'PENDING', 'test-v1', ?, ?, ?, ?, ?, 5
                )
                """,
                deliveryId,
                eventId,
                userId,
                subscriptionId,
                text,
                sha256(text),
                Timestamp.from(now.minusSeconds(1)),
                Timestamp.from(now.minusSeconds(1)),
                Timestamp.from(now.plusSeconds(3600))
        );
        return new Fixture(deliveryId, subscriptionId, userId, storeId);
    }

    private TelegramSendException failure(
            TelegramSendFailureKind kind,
            Integer status,
            Instant retryAfterAt
    ) {
        return new TelegramSendException(
                kind,
                "Safe Telegram failure",
                status,
                retryAfterAt,
                null
        );
    }

    private String status(UUID deliveryId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM notification_deliveries WHERE id = ?",
                String.class,
                deliveryId
        );
    }

    private long positive(long value) {
        return value == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(value);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)
                    )
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Fixture(
            UUID deliveryId,
            UUID subscriptionId,
            UUID userId,
            UUID storeId
    ) {
    }
}
