package com.storeanalytics.notification.delivery;

import com.storeanalytics.notification.config.TelegramNotificationProperties;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class TelegramDeliveryCircuitBreaker {

    private final TelegramNotificationProperties properties;
    private final AtomicReference<Instant> authenticationOpenUntil =
            new AtomicReference<>(Instant.EPOCH);

    public TelegramDeliveryCircuitBreaker(
            TelegramNotificationProperties properties
    ) {
        this.properties = properties;
    }

    public boolean permits(Instant now) {
        return !authenticationOpenUntil.get().isAfter(now);
    }

    public void authenticationFailed(Instant now) {
        authenticationOpenUntil.accumulateAndGet(
                now.plus(properties.deliveryRetryMaxDelay()),
                (current, candidate) -> current.isAfter(candidate) ? current : candidate
        );
    }

    public void providerSucceeded() {
        authenticationOpenUntil.set(Instant.EPOCH);
    }
}
