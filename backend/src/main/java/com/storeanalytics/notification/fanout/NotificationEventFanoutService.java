package com.storeanalytics.notification.fanout;

import com.storeanalytics.interpretation.contract.LlmCanonicalJsonCodec;
import com.storeanalytics.notification.config.TelegramNotificationProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
public class NotificationEventFanoutService {

    private final NotificationEventFanoutStore store;
    private final LlmCanonicalJsonCodec jsonCodec;
    private final WeeklyTelegramMessageRenderer renderer;
    private final TelegramQuietHoursScheduler quietHoursScheduler;
    private final TelegramNotificationProperties properties;
    private final NotificationFanoutMetrics metrics;
    private final Clock clock;

    public NotificationEventFanoutService(
            NotificationEventFanoutStore store,
            LlmCanonicalJsonCodec jsonCodec,
            WeeklyTelegramMessageRenderer renderer,
            TelegramQuietHoursScheduler quietHoursScheduler,
            TelegramNotificationProperties properties,
            NotificationFanoutMetrics metrics,
            Clock clock
    ) {
        this.store = store;
        this.jsonCodec = jsonCodec;
        this.renderer = renderer;
        this.quietHoursScheduler = quietHoursScheduler;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional
    public Optional<NotificationFanoutResult> processNext() {
        Instant now = clock.instant();
        Optional<WeeklyNotificationEvent> claimed = store.claimNextWeekly(now);
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        WeeklyNotificationEvent event = claimed.get();
        if (!now.isBefore(event.expiresAt())) {
            return Optional.of(complete(new NotificationFanoutResult(
                    event.id(),
                    NotificationFanoutOutcome.EVENT_EXPIRED,
                    0,
                    0
            ), now));
        }

        JsonNode content = jsonCodec.decodeVerified(
                event.contentPayload(),
                event.contentHash()
        );
        Map<String, String> employeeNames = store.employeeNames(event.snapshotId());
        RenderedTelegramMessage message = renderer.render(
                event,
                content,
                employeeNames
        );
        List<TelegramNotificationRecipient> recipients = store.recipients(
                event.storeId(),
                event.eventType(),
                properties.botCode()
        );
        if (recipients.isEmpty()) {
            return Optional.of(complete(new NotificationFanoutResult(
                    event.id(),
                    NotificationFanoutOutcome.NO_RECIPIENTS,
                    0,
                    0
            ), now));
        }

        int deliveryCount = 0;
        Instant earliest = event.notBefore().isAfter(now) ? event.notBefore() : now;
        for (TelegramNotificationRecipient recipient : recipients) {
            TelegramDeliverySchedule schedule = quietHoursScheduler.schedule(
                    earliest,
                    event.expiresAt(),
                    recipient
            );
            deliveryCount += store.insertDelivery(
                    event,
                    recipient,
                    message,
                    schedule,
                    properties.renderVersion(),
                    properties.maxAttempts(),
                    now
            );
        }
        NotificationFanoutResult result = new NotificationFanoutResult(
                event.id(),
                NotificationFanoutOutcome.DELIVERIES_CREATED,
                recipients.size(),
                deliveryCount
        );
        return Optional.of(complete(result, now));
    }

    private NotificationFanoutResult complete(
            NotificationFanoutResult result,
            Instant now
    ) {
        store.insertReceipt(result, now);
        metrics.completed(result.outcome());
        return result;
    }
}
