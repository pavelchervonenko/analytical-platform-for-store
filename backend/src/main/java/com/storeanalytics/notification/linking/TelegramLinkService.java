package com.storeanalytics.notification.linking;

import static com.storeanalytics.audit.service.AuditAction.TELEGRAM_DELIVERY_SETTINGS_CHANGED;
import static com.storeanalytics.audit.service.AuditEntityType.TELEGRAM_LINK_TOKEN;
import static com.storeanalytics.audit.service.AuditEntityType.TELEGRAM_SUBSCRIPTION;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.audit.service.AuditAction;
import com.storeanalytics.audit.service.AuditLogService;
import com.storeanalytics.audit.service.AuditTarget;

import com.storeanalytics.common.exception.PreconditionFailedException;
import com.storeanalytics.common.exception.PreconditionRequiredException;
import com.storeanalytics.common.web.StrongEtag;
import com.storeanalytics.notification.config.TelegramNotificationProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TelegramLinkService {

    private final TelegramLinkStore store;
    private final TelegramLinkTokenGenerator tokenGenerator;
    private final TelegramNotificationProperties properties;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public TelegramLinkService(
            TelegramLinkStore store,
            TelegramLinkTokenGenerator tokenGenerator,
            TelegramNotificationProperties properties,
            AuditLogService auditLogService,
            Clock clock
    ) {
        this.store = store;
        this.tokenGenerator = tokenGenerator;
        this.properties = properties;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public TelegramChannelView get(UUID userId) {
        UUID actor = requireNonNull(userId, "userId");
        Instant now = clock.instant();
        Optional<TelegramSubscriptionRow> current = store.findCurrent(
                actor,
                properties.botCode(),
                now
        );
        if (current.isPresent()) {
            return view(current.get(), null);
        }
        Instant linkExpiry = store.findOpenLinkExpiry(
                actor,
                properties.botCode(),
                now
        ).orElse(null);
        if (linkExpiry != null) {
            return new TelegramChannelView(
                    TelegramChannelState.LINK_ISSUED,
                    null,
                    null,
                    linkExpiry,
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of(TelegramChannelAction.OPEN_BOT),
                    publicBotUrl()
            );
        }
        return notLinked();
    }

    @Transactional
    public TelegramLinkCreatedView createLink(UUID userId) {
        requireLinkingEnabled();
        UUID actor = requireNonNull(userId, "userId");
        Instant now = clock.instant();
        if (!store.lockActiveUser(actor)) {
            throw new TelegramLinkStateConflictException(
                    "Telegram link actor is missing or inactive"
            );
        }
        if (store.findCurrent(actor, properties.botCode(), now).isPresent()) {
            throw new TelegramLinkStateConflictException(
                    "Telegram channel already has a current subscription"
            );
        }
        enforceRateLimit(actor, now);
        boolean relink = store.hasSubscriptionHistory(actor, properties.botCode());
        store.expirePendingAndCloseTokens(actor, properties.botCode(), now);
        GeneratedTelegramLinkToken token = tokenGenerator.generate();
        Instant expiresAt = now.plus(properties.linkTokenTtl());
        UUID tokenId = UUID.randomUUID();
        store.insertLinkToken(
                tokenId,
                actor,
                properties.botCode(),
                relink ? "RELINK" : "LINK",
                token.hash(),
                expiresAt,
                now
        );
        auditLogService.record(
                actor,
                null,
                AuditAction.TELEGRAM_LINK_ISSUED,
                new AuditTarget(TELEGRAM_LINK_TOKEN, tokenId),
                null,
                null,
                null
        );
        return new TelegramLinkCreatedView(
                publicBotUrl() + "?start=" + token.value(),
                expiresAt
        );
    }

    @Transactional
    public TelegramChannelView confirm(UUID userId, String ifMatch) {
        requireLinkingEnabled();
        UUID actor = requireNonNull(userId, "userId");
        Instant now = clock.instant();
        TelegramSubscriptionRow subscription = store.lockCurrent(
                actor,
                properties.botCode(),
                now
        ).filter(value -> "PENDING_CONFIRMATION".equals(value.status()))
                .orElseThrow(() -> new TelegramLinkStateConflictException(
                        "There is no pending Telegram subscription to confirm"
                ));
        requireEtag(subscription, ifMatch);
        store.confirm(subscription.id(), now);
        auditLogService.record(
                actor,
                null,
                AuditAction.TELEGRAM_LINK_CONFIRMED,
                new AuditTarget(TELEGRAM_SUBSCRIPTION, subscription.id()),
                null,
                null,
                null
        );
        return store.findCurrent(actor, properties.botCode(), now)
                .map(value -> view(value, null))
                .orElseThrow(() -> new IllegalStateException(
                        "Confirmed Telegram subscription is unavailable"
                ));
    }

    @Transactional
    public TelegramChannelView updateSettings(
            UUID userId,
            TelegramDeliverySettingsRequest request,
            String ifMatch
    ) {
        UUID actor = requireNonNull(userId, "userId");
        TelegramDeliverySettingsRequest settings = requireNonNull(
                request,
                "request"
        );
        Instant now = clock.instant();
        TelegramSubscriptionRow subscription = store.lockCurrent(
                actor,
                properties.botCode(),
                now
        ).filter(value -> "ACTIVE".equals(value.status()))
                .orElseThrow(() -> new TelegramLinkStateConflictException(
                        "There is no active Telegram subscription to configure"
                ));
        requireEtag(subscription, ifMatch);
        if (sameSettings(subscription, settings)) {
            return view(subscription, null);
        }
        store.updateDeliverySettings(
                subscription.id(),
                settings.timezone(),
                settings.quietHoursEnabled(),
                settings.quietHoursStart(),
                settings.quietHoursEnd()
        );
        auditLogService.record(
                actor,
                null,
                TELEGRAM_DELIVERY_SETTINGS_CHANGED,
                new AuditTarget(TELEGRAM_SUBSCRIPTION, subscription.id()),
                null,
                settingsMap(subscription),
                Map.of(
                        "timezone", settings.timezone(),
                        "quietHoursEnabled", settings.quietHoursEnabled(),
                        "quietHoursStart", settings.quietHoursStart(),
                        "quietHoursEnd", settings.quietHoursEnd()
                )
        );
        return store.findCurrent(actor, properties.botCode(), now)
                .map(value -> view(value, null))
                .orElseThrow(() -> new IllegalStateException(
                        "Updated Telegram subscription is unavailable"
                ));
    }

    @Transactional
    public TelegramChannelView revoke(UUID userId, String ifMatch) {
        UUID actor = requireNonNull(userId, "userId");
        Instant now = clock.instant();
        TelegramSubscriptionRow subscription = store.lockCurrent(
                actor,
                properties.botCode(),
                now
        ).orElseThrow(() -> new TelegramLinkStateConflictException(
                "There is no Telegram subscription to revoke"
        ));
        requireEtag(subscription, ifMatch);
        store.revoke(subscription.id(), actor, properties.botCode(), now);
        auditLogService.record(
                actor,
                null,
                AuditAction.TELEGRAM_LINK_REVOKED,
                new AuditTarget(TELEGRAM_SUBSCRIPTION, subscription.id()),
                null,
                null,
                null
        );
        return notLinked();
    }

    public static String etag(TelegramChannelView view) {
        if (view.subscriptionId() == null || view.version() == null) {
            return null;
        }
        return StrongEtag.of(
                "telegram-channel",
                view.subscriptionId(),
                view.version()
        );
    }

    private void enforceRateLimit(UUID userId, Instant now) {
        Optional<Instant> lastCreated = store.lastLinkCreatedAt(
                userId,
                properties.botCode()
        );
        if (lastCreated.isPresent() && lastCreated.get().plus(
                properties.linkIssueMinInterval()
        ).isAfter(now)) {
            throw new TelegramLinkThrottledException(
                    "Telegram link was issued too recently"
            );
        }
        int issued = store.countLinksSince(
                userId,
                properties.botCode(),
                now.minus(1, ChronoUnit.HOURS)
        );
        if (issued >= properties.linkMaxPerHour()) {
            throw new TelegramLinkThrottledException(
                    "Telegram hourly link issue limit is exhausted"
            );
        }
    }

    private void requireEtag(TelegramSubscriptionRow subscription, String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new PreconditionRequiredException(
                    "If-Match is required for Telegram channel transition"
            );
        }
        String expected = StrongEtag.of(
                "telegram-channel",
                subscription.id(),
                subscription.version()
        );
        if (!expected.equals(ifMatch.trim())) {
            throw new PreconditionFailedException(
                    "Telegram channel was changed by another request"
            );
        }
    }

    private boolean sameSettings(
            TelegramSubscriptionRow subscription,
            TelegramDeliverySettingsRequest settings
    ) {
        return subscription.deliveryTimezone().equals(settings.timezone())
                && subscription.quietHoursEnabled() == settings.quietHoursEnabled()
                && subscription.quietHoursStart().equals(settings.quietHoursStart())
                && subscription.quietHoursEnd().equals(settings.quietHoursEnd());
    }

    private Map<String, ?> settingsMap(TelegramSubscriptionRow subscription) {
        return Map.of(
                "timezone", subscription.deliveryTimezone(),
                "quietHoursEnabled", subscription.quietHoursEnabled(),
                "quietHoursStart", subscription.quietHoursStart(),
                "quietHoursEnd", subscription.quietHoursEnd()
        );
    }

    private TelegramChannelView view(
            TelegramSubscriptionRow subscription,
            Instant linkExpiresAt
    ) {
        TelegramChannelState state = switch (subscription.status()) {
            case "PENDING_CONFIRMATION" -> TelegramChannelState.PENDING_CONFIRMATION;
            case "ACTIVE" -> TelegramChannelState.ACTIVE;
            case "BOT_BLOCKED" -> TelegramChannelState.BOT_BLOCKED;
            default -> throw new IllegalArgumentException(
                    "Unsupported current Telegram subscription status"
            );
        };
        List<TelegramChannelAction> actions = switch (state) {
            case PENDING_CONFIRMATION -> List.of(
                    TelegramChannelAction.CONFIRM,
                    TelegramChannelAction.REVOKE,
                    TelegramChannelAction.OPEN_BOT
            );
            case ACTIVE -> List.of(
                    TelegramChannelAction.REVOKE,
                    TelegramChannelAction.UPDATE_SETTINGS,
                    TelegramChannelAction.OPEN_BOT
            );
            case BOT_BLOCKED -> List.of(
                    TelegramChannelAction.REVOKE,
                    TelegramChannelAction.OPEN_BOT
            );
            default -> List.of(TelegramChannelAction.LINK);
        };
        return new TelegramChannelView(
                state,
                subscription.id(),
                subscription.version(),
                linkExpiresAt,
                "PENDING_CONFIRMATION".equals(subscription.status())
                        ? subscription.createdAt() : null,
                subscription.confirmedAt(),
                subscription.blockedAt(),
                maskedDestination(subscription.telegramChatId()),
                new TelegramDeliverySettingsView(
                        subscription.deliveryTimezone(),
                        subscription.quietHoursEnabled(),
                        subscription.quietHoursStart(),
                        subscription.quietHoursEnd()
                ),
                actions,
                publicBotUrl()
        );
    }

    private TelegramChannelView notLinked() {
        return new TelegramChannelView(
                TelegramChannelState.NOT_LINKED,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                linkingAvailable()
                        ? List.of(TelegramChannelAction.LINK)
                        : List.of(),
                publicBotUrl()
        );
    }

    private String maskedDestination(long chatId) {
        String value = Long.toString(chatId).replace("-", "");
        int start = Math.max(0, value.length() - 4);
        return "Telegram •••" + value.substring(start);
    }

    private String publicBotUrl() {
        String username = properties.botUsername();
        return username == null || username.isBlank()
                ? null : "https://t.me/" + username;
    }

    private boolean linkingAvailable() {
        return properties.enabled() && properties.linkingEnabled()
                && publicBotUrl() != null;
    }

    private void requireLinkingEnabled() {
        if (!linkingAvailable()) {
            throw new TelegramLinkStateConflictException(
                    "Telegram linking is not enabled"
            );
        }
    }
}
