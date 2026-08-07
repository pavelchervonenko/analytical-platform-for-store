package com.storeanalytics.notification.linking;

import static com.storeanalytics.audit.service.AuditEntityType.TELEGRAM_SUBSCRIPTION;

import com.storeanalytics.audit.service.AuditAction;
import com.storeanalytics.audit.service.AuditLogService;
import com.storeanalytics.audit.service.AuditTarget;
import com.storeanalytics.notification.config.TelegramNotificationProperties;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class TelegramWebhookService {

    private static final Pattern START_COMMAND = Pattern.compile(
            "^/start(?:@[A-Za-z0-9_]+)?[ ]+([A-Za-z0-9_-]{1,64})$"
    );

    private final TelegramWebhookStore webhookStore;
    private final TelegramLinkStore linkStore;
    private final TelegramLinkTokenGenerator tokenGenerator;
    private final TelegramNotificationProperties properties;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TelegramWebhookService(
            TelegramWebhookStore webhookStore,
            TelegramLinkStore linkStore,
            TelegramLinkTokenGenerator tokenGenerator,
            TelegramNotificationProperties properties,
            AuditLogService auditLogService,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.webhookStore = webhookStore;
        this.linkStore = linkStore;
        this.tokenGenerator = tokenGenerator;
        this.properties = properties;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public TelegramWebhookOutcome process(
            String botCode,
            TelegramUpdateCommand command
    ) {
        Instant now = clock.instant();
        String updateType = command.membership() != null
                ? "MY_CHAT_MEMBER"
                : command.message() == null ? "UNSUPPORTED" : "MESSAGE";
        int inserted = webhookStore.insertReceipt(
                botCode,
                command.updateId(),
                updateType,
                payloadHash(command),
                now
        );
        if (inserted == 0) {
            return TelegramWebhookOutcome.DUPLICATE;
        }
        TelegramWebhookOutcome outcome = command.membership() == null
                ? processMessage(botCode, command, now)
                : processMembership(botCode, command, now);
        webhookStore.updateOutcome(botCode, command.updateId(), outcome, now);
        return outcome;
    }

    private TelegramWebhookOutcome processMembership(
            String botCode,
            TelegramUpdateCommand command,
            Instant now
    ) {
        TelegramUpdateCommand.TelegramMembershipCommand membership =
                command.membership();
        if (membership == null || !"private".equals(membership.chatType())
                || membership.newStatus() == null
                || membership.newStatus().isBlank()) {
            return TelegramWebhookOutcome.IGNORED;
        }
        String telegramStatus = membership.newStatus()
                .strip()
                .toLowerCase(Locale.ROOT);
        Optional<TelegramMembershipTransition> transition =
                linkStore.applyMembershipUpdate(
                        botCode,
                        membership.chatId(),
                        command.updateId(),
                        telegramStatus,
                        now
                );
        if (transition.isEmpty() || transition.get().stale()
                || !transition.get().stateChanged()) {
            return TelegramWebhookOutcome.IGNORED;
        }
        TelegramMembershipTransition changed = transition.get();
        AuditAction action = "ACTIVE".equals(changed.currentStatus())
                ? AuditAction.TELEGRAM_BOT_UNBLOCKED
                : AuditAction.TELEGRAM_BOT_BLOCKED;
        auditLogService.recordSystem(
                null,
                action,
                new AuditTarget(TELEGRAM_SUBSCRIPTION, changed.subscriptionId()),
                "Telegram my_chat_member lifecycle update",
                Map.of("status", changed.previousStatus()),
                Map.of(
                        "status", changed.currentStatus(),
                        "updateId", command.updateId()
                )
        );
        return TelegramWebhookOutcome.PROCESSED;
    }

    private TelegramWebhookOutcome processMessage(
            String botCode,
            TelegramUpdateCommand command,
            Instant now
    ) {
        TelegramUpdateCommand.TelegramMessageCommand message = command.message();
        if (message == null || message.senderBot()
                || !"private".equals(message.chatType())
                || message.text() == null) {
            return TelegramWebhookOutcome.IGNORED;
        }
        Matcher matcher = START_COMMAND.matcher(message.text().strip());
        if (!matcher.matches()) {
            return TelegramWebhookOutcome.IGNORED;
        }
        String tokenValue = matcher.group(1);
        if (!tokenValue.startsWith("v1_")) {
            return TelegramWebhookOutcome.REJECTED;
        }
        Optional<TelegramLinkTokenRow> token = linkStore.lockUsableToken(
                botCode,
                tokenGenerator.hash(tokenValue),
                now
        );
        if (token.isEmpty()) {
            return TelegramWebhookOutcome.REJECTED;
        }
        TelegramLinkTokenRow linkToken = token.get();
        if (!linkStore.lockActiveUser(linkToken.userId())) {
            return TelegramWebhookOutcome.REJECTED;
        }
        webhookStore.lockDestination(message.telegramUserId(), message.chatId());
        linkStore.lockDestination(
                botCode,
                message.telegramUserId(),
                message.chatId()
        );
        if (linkStore.destinationBelongsToAnotherUser(
                botCode,
                message.telegramUserId(),
                message.chatId(),
                linkToken.userId()
        )) {
            return TelegramWebhookOutcome.REJECTED;
        }
        Optional<TelegramSubscriptionRow> current = linkStore.lockCurrent(
                linkToken.userId(),
                botCode,
                now
        );
        if (current.isPresent()) {
            TelegramSubscriptionRow subscription = current.get();
            if (subscription.telegramChatId() != message.chatId()
                    || !"PENDING_CONFIRMATION".equals(subscription.status())) {
                return TelegramWebhookOutcome.REJECTED;
            }
            linkStore.consumeToken(linkToken.id(), subscription.id(), now);
            linkStore.enqueueLinkConfirmation(
                    linkToken.userId(),
                    subscription.id(),
                    now,
                    subscription.pendingExpiresAt(),
                    properties.maxAttempts()
            );
            return TelegramWebhookOutcome.PROCESSED;
        }

        Instant pendingExpiresAt = now.plus(properties.pendingConfirmationTtl());
        UUID subscriptionId = linkStore.insertPendingSubscription(
                linkToken.userId(),
                botCode,
                message.telegramUserId(),
                message.chatId(),
                pendingExpiresAt,
                now
        );
        linkStore.consumeToken(linkToken.id(), subscriptionId, now);
        linkStore.enqueueLinkConfirmation(
                linkToken.userId(),
                subscriptionId,
                now,
                pendingExpiresAt,
                properties.maxAttempts()
        );
        auditLogService.record(
                linkToken.userId(),
                null,
                AuditAction.TELEGRAM_LINK_PENDING,
                new AuditTarget(TELEGRAM_SUBSCRIPTION, subscriptionId),
                null,
                null,
                null
        );
        return TelegramWebhookOutcome.PROCESSED;
    }

    private String payloadHash(TelegramUpdateCommand command) {
        try {
            byte[] payload = objectMapper.writeValueAsBytes(command);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload);
            return HexFormat.of().formatHex(digest);
        } catch (JacksonException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "Telegram update receipt hash cannot be calculated",
                    exception
            );
        }
    }
}
