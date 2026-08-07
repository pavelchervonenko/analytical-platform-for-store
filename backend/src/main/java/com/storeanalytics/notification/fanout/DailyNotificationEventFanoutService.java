package com.storeanalytics.notification.fanout;

import com.storeanalytics.notification.config.TelegramNotificationProperties;
import com.storeanalytics.notification.daily.DailyStorePulsePayload;
import com.storeanalytics.notification.daily.DailyStorePulsePayloadCodec;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DailyNotificationEventFanoutService {


    private final DailyNotificationFanoutStore store;
    private final DailyStorePulsePayloadCodec codec;
    private final DailyTelegramMessageRenderer renderer;
    private final TelegramQuietHoursScheduler quietHoursScheduler;
    private final TelegramNotificationProperties properties;
    private final NotificationFanoutMetrics metrics;
    private final Clock clock;

    public DailyNotificationEventFanoutService(
            DailyNotificationFanoutStore store,
            DailyStorePulsePayloadCodec codec,
            DailyTelegramMessageRenderer renderer,
            TelegramQuietHoursScheduler quietHoursScheduler,
            TelegramNotificationProperties properties,
            NotificationFanoutMetrics metrics,
            Clock clock
    ) {
        this.store = store;
        this.codec = codec;
        this.renderer = renderer;
        this.quietHoursScheduler = quietHoursScheduler;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional
    public Optional<NotificationFanoutResult> processNext() {
        Instant now = clock.instant();
        Optional<DailyNotificationEvent> claimed = store.claimNext(now);
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        DailyNotificationEvent event = claimed.get();
        if (!now.isBefore(event.expiresAt())) {
            return Optional.of(complete(new NotificationFanoutResult(
                    event.id(), NotificationFanoutOutcome.EVENT_EXPIRED, 0, 0
            ), now));
        }
        DailyStorePulsePayload payload = codec.decodeVerified(
                event.eventPayload(), event.payloadHash()
        );
        RenderedTelegramMessage message = renderer.render(event, payload);
        List<TelegramNotificationRecipient> recipients = store.recipients(
                event.storeId(), event.eventType(), properties.botCode()
        );
        if (recipients.isEmpty()) {
            return Optional.of(complete(new NotificationFanoutResult(
                    event.id(), NotificationFanoutOutcome.NO_RECIPIENTS, 0, 0
            ), now));
        }
        int deliveries = 0;
        Instant earliest = event.notBefore().isAfter(now) ? event.notBefore() : now;
        for (TelegramNotificationRecipient recipient : recipients) {
            TelegramDeliverySchedule schedule = quietHoursScheduler.schedule(
                    earliest, event.expiresAt(), recipient
            );
            deliveries += store.insertDelivery(
                    event,
                    recipient,
                    message,
                    schedule,
                    codec.renderVersion(),
                    properties.maxAttempts(),
                    now
            );
        }
        return Optional.of(complete(new NotificationFanoutResult(
                event.id(),
                NotificationFanoutOutcome.DELIVERIES_CREATED,
                recipients.size(),
                deliveries
        ), now));
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
