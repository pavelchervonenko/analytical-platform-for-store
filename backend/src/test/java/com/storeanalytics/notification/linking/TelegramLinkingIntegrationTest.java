package com.storeanalytics.notification.linking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Instant;
import java.time.LocalTime;
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
class TelegramLinkingIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private TelegramLinkService linkService;

    @Autowired
    private TelegramWebhookService webhookService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.notification.telegram.enabled", () -> "true");
        registry.add("app.notification.telegram.linking-enabled", () -> "true");
        registry.add("app.notification.telegram.webhook-enabled", () -> "true");
        registry.add("app.notification.telegram.bot-code", () -> "primary");
        registry.add(
                "app.notification.telegram.bot-username",
                () -> "storeanalytics_test_bot"
        );
        registry.add(
                "app.notification.telegram.webhook-secret",
                () -> "test_webhook_secret_123456789"
        );
    }

    @Test
    void completesTwoStepLinkAndRevocationWithoutStoringPlaintextToken() {
        UUID owner = insertUser();
        UUID anotherUser = insertUser();

        TelegramLinkCreatedView created = linkService.createLink(owner);
        String token = token(created.deepLink());

        assertThat(token).matches("v1_[A-Za-z0-9_-]{32}");
        assertThat(token.length()).isLessThanOrEqualTo(64);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT token_hash FROM telegram_link_tokens WHERE user_id = ?",
                String.class,
                owner
        )).matches("[a-f0-9]{64}").isNotEqualTo(token);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM telegram_link_tokens "
                        + "WHERE token_hash = ? OR token_hash = ?",
                Integer.class,
                token,
                created.deepLink()
        )).isZero();

        TelegramUpdateCommand update = startUpdate(9001L, token);
        assertThat(webhookService.process("primary", update))
                .isEqualTo(TelegramWebhookOutcome.PROCESSED);
        assertThat(webhookService.process("primary", update))
                .isEqualTo(TelegramWebhookOutcome.DUPLICATE);
        UUID linkConfirmationDeliveryId = jdbcTemplate.queryForObject(
                "SELECT id FROM notification_deliveries "
                        + "WHERE recipient_user_id = ? "
                        + "AND delivery_kind = 'LINK_CONFIRMATION'",
                UUID.class,
                owner
        );
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_deliveries "
                        + "WHERE subscription_id = ? "
                        + "AND delivery_kind = 'LINK_CONFIRMATION'",
                Integer.class,
                jdbcTemplate.queryForObject(
                        "SELECT subscription_id FROM notification_deliveries "
                                + "WHERE id = ?",
                        UUID.class,
                        linkConfirmationDeliveryId
                )
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT event_id IS NULL FROM notification_deliveries WHERE id = ?",
                Boolean.class,
                linkConfirmationDeliveryId
        )).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT rendered_text FROM notification_deliveries WHERE id = ?",
                String.class,
                linkConfirmationDeliveryId
        )).contains("подтвердите подключение");

        TelegramChannelView pending = linkService.get(owner);
        assertThat(pending.state()).isEqualTo(
                TelegramChannelState.PENDING_CONFIRMATION
        );
        assertThat(pending.destination()).startsWith("Telegram •••");
        assertThat(pending.allowedActions()).contains(
                TelegramChannelAction.CONFIRM,
                TelegramChannelAction.REVOKE
        );
        assertThatThrownBy(() -> linkService.confirm(
                anotherUser,
                TelegramLinkService.etag(pending)
        )).isInstanceOf(TelegramLinkStateConflictException.class);

        TelegramChannelView active = linkService.confirm(
                owner,
                TelegramLinkService.etag(pending)
        );
        assertThat(active.state()).isEqualTo(TelegramChannelState.ACTIVE);
        assertThat(active.confirmedAt()).isBeforeOrEqualTo(Instant.now());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM telegram_subscriptions "
                        + "WHERE user_id = ? AND status = 'ACTIVE'",
                Integer.class,
                owner
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM notification_deliveries WHERE id = ?",
                String.class,
                linkConfirmationDeliveryId
        )).isEqualTo("CANCELLED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT error_code FROM notification_deliveries WHERE id = ?",
                String.class,
                linkConfirmationDeliveryId
        )).isEqualTo("LINK_CONFIRMED");

        TelegramChannelView revoked = linkService.revoke(
                owner,
                TelegramLinkService.etag(active)
        );
        assertThat(revoked.state()).isEqualTo(TelegramChannelState.NOT_LINKED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM telegram_subscriptions "
                        + "WHERE user_id = ? AND status = 'REVOKED'",
                Integer.class,
                owner
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log WHERE actor_user_id = ? "
                        + "AND action LIKE 'TELEGRAM_LINK_%'",
                Integer.class,
                owner
        )).isEqualTo(4);
    }

    @Test
    void membershipUpdatesPreserveConfirmationAndIgnoreStaleUpdates() {
        UUID owner = insertUser();
        TelegramLinkCreatedView created = linkService.createLink(owner);
        TelegramUpdateCommand start = startUpdate(
                9020L,
                token(created.deepLink())
        );
        webhookService.process("primary", start);
        long chatId = start.message().chatId();

        assertThat(webhookService.process(
                "primary",
                membershipUpdate(9021L, chatId, "kicked", "member")
        )).isEqualTo(TelegramWebhookOutcome.IGNORED);
        TelegramChannelView pending = linkService.get(owner);
        assertThat(pending.state()).isEqualTo(
                TelegramChannelState.PENDING_CONFIRMATION
        );

        TelegramChannelView active = linkService.confirm(
                owner,
                TelegramLinkService.etag(pending)
        );
        assertThat(webhookService.process(
                "primary",
                membershipUpdate(9023L, chatId, "member", "kicked")
        )).isEqualTo(TelegramWebhookOutcome.PROCESSED);
        TelegramChannelView blocked = linkService.get(owner);
        assertThat(blocked.state()).isEqualTo(TelegramChannelState.BOT_BLOCKED);
        assertThat(blocked.blockedAt()).isNotNull();
        assertThat(blocked.version()).isEqualTo(active.version() + 1);

        TelegramUpdateCommand stale = membershipUpdate(
                9022L,
                chatId,
                "kicked",
                "member"
        );
        assertThat(webhookService.process("primary", stale))
                .isEqualTo(TelegramWebhookOutcome.IGNORED);
        assertThat(webhookService.process("primary", stale))
                .isEqualTo(TelegramWebhookOutcome.DUPLICATE);
        assertThat(linkService.get(owner).state()).isEqualTo(
                TelegramChannelState.BOT_BLOCKED
        );

        jdbcTemplate.update(
                "UPDATE telegram_subscriptions "
                        + "SET last_membership_update_at = now() - interval '8 days' "
                        + "WHERE id = ?",
                blocked.subscriptionId()
        );
        assertThat(webhookService.process(
                "primary",
                membershipUpdate(101L, chatId, "kicked", "member")
        )).isEqualTo(TelegramWebhookOutcome.PROCESSED);
        TelegramChannelView restored = linkService.get(owner);
        assertThat(restored.state()).isEqualTo(TelegramChannelState.ACTIVE);
        assertThat(restored.blockedAt()).isNull();
        assertThat(restored.version()).isEqualTo(blocked.version() + 1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT last_membership_update_id FROM telegram_subscriptions "
                        + "WHERE id = ?",
                Long.class,
                restored.subscriptionId()
        )).isEqualTo(101L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log WHERE entity_id = ? "
                        + "AND action IN ('TELEGRAM_BOT_BLOCKED', "
                        + "'TELEGRAM_BOT_UNBLOCKED')",
                Integer.class,
                restored.subscriptionId().toString()
        )).isEqualTo(2);
    }

    @Test
    void explicitEtagIsRequiredForConfirmation() {
        UUID owner = insertUser();
        TelegramLinkCreatedView created = linkService.createLink(owner);
        webhookService.process("primary", startUpdate(9010L, token(created.deepLink())));

        assertThatThrownBy(() -> linkService.confirm(owner, null))
                .isInstanceOf(
                        com.storeanalytics.common.exception
                                .PreconditionRequiredException.class
                );
        assertThatThrownBy(() -> linkService.confirm(owner, "\"stale\""))
                .isInstanceOf(
                        com.storeanalytics.common.exception
                                .PreconditionFailedException.class
                );
    }

    @Test
    void updatesActiveDeliverySettingsWithOptimisticConcurrencyAndAudit() {
        UUID owner = insertUser();
        TelegramLinkCreatedView created = linkService.createLink(owner);
        webhookService.process(
                "primary",
                startUpdate(9030L, token(created.deepLink()))
        );
        TelegramChannelView pending = linkService.get(owner);
        TelegramChannelView active = linkService.confirm(
                owner,
                TelegramLinkService.etag(pending)
        );
        TelegramDeliverySettingsRequest request =
                new TelegramDeliverySettingsRequest(
                        "Europe/Moscow",
                        true,
                        LocalTime.of(22, 0),
                        LocalTime.of(7, 30)
                );

        TelegramChannelView updated = linkService.updateSettings(
                owner,
                request,
                TelegramLinkService.etag(active)
        );

        assertThat(updated.version()).isEqualTo(active.version() + 1);
        assertThat(updated.deliverySettings()).isEqualTo(
                new TelegramDeliverySettingsView(
                        "Europe/Moscow",
                        true,
                        LocalTime.of(22, 0),
                        LocalTime.of(7, 30)
                )
        );
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log WHERE actor_user_id = ? "
                        + "AND action = 'TELEGRAM_DELIVERY_SETTINGS_CHANGED'",
                Integer.class,
                owner
        )).isOne();
        assertThatThrownBy(() -> linkService.updateSettings(
                owner,
                request,
                TelegramLinkService.etag(active)
        )).isInstanceOf(
                com.storeanalytics.common.exception.PreconditionFailedException.class
        );
    }

    private UUID insertUser() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO app_users (
                    id, email, password_hash, display_name, role,
                    password_change_required
                ) VALUES (?, ?, '{noop}test', 'Manager', 'MANAGER', false)
                """,
                id,
                "telegram-" + id + "@example.test"
        );
        return id;
    }

    private TelegramUpdateCommand startUpdate(long updateId, String token) {
        return new TelegramUpdateCommand(
                updateId,
                new TelegramUpdateCommand.TelegramMessageCommand(
                        123_456_789L + updateId,
                        false,
                        9_876_000_000L + updateId,
                        "private",
                        "/start " + token
                ),
                null
        );
    }

    private TelegramUpdateCommand membershipUpdate(
            long updateId,
            long chatId,
            String oldStatus,
            String newStatus
    ) {
        return new TelegramUpdateCommand(
                updateId,
                null,
                new TelegramUpdateCommand.TelegramMembershipCommand(
                        chatId,
                        "private",
                        oldStatus,
                        newStatus
                )
        );
    }

    private String token(String deepLink) {
        String query = URI.create(deepLink).getQuery();
        return query.substring("start=".length());
    }
}
