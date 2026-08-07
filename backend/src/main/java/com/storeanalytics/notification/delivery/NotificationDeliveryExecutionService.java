package com.storeanalytics.notification.delivery;

import com.storeanalytics.common.config.ApplicationRole;
import com.storeanalytics.common.config.ConditionalOnApplicationRole;
import com.storeanalytics.notification.config.TelegramNotificationProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnApplicationRole({ApplicationRole.WORKER, ApplicationRole.COMBINED})
public class NotificationDeliveryExecutionService {

    private final NotificationDeliveryPersistence persistence;
    private final TelegramSender sender;
    private final TelegramNotificationProperties properties;
    private final NotificationDeliveryMetrics metrics;
    private final TelegramDeliveryCircuitBreaker circuitBreaker;
    private final Clock clock;

    public NotificationDeliveryExecutionService(
            NotificationDeliveryPersistence persistence,
            TelegramSender sender,
            TelegramNotificationProperties properties,
            NotificationDeliveryMetrics metrics,
            TelegramDeliveryCircuitBreaker circuitBreaker,
            Clock clock
    ) {
        this.persistence = persistence;
        this.sender = sender;
        this.properties = properties;
        this.metrics = metrics;
        this.circuitBreaker = circuitBreaker;
        this.clock = clock;
    }

    public boolean processNext(String owner) {
        Instant now = clock.instant();
        if (!circuitBreaker.permits(now)) {
            metrics.outcome("circuit_open");
            return false;
        }
        Optional<NotificationDeliveryClaim> claim = persistence.claimNext(
                owner,
                now,
                properties.deliveryLeaseDuration()
        );
        if (claim.isEmpty()) {
            return false;
        }
        Optional<PreparedTelegramDelivery> prepared = persistence.prepareAttempt(
                claim.get(),
                clock.instant()
        );
        if (prepared.isEmpty()) {
            metrics.outcome("preflight_terminal");
            return true;
        }
        send(prepared.get());
        return true;
    }

    private void send(PreparedTelegramDelivery delivery) {
        Instant startedAt = clock.instant();
        try {
            TelegramSendReceipt receipt = sender.send(new TelegramSendRequest(
                    delivery.deliveryId(),
                    delivery.chatId(),
                    delivery.text(),
                    earlier(
                            delivery.expiresAt(),
                            startedAt.plus(properties.readTimeout())
                    )
            ));
            persistence.completeSuccess(delivery, receipt, clock.instant());
            circuitBreaker.providerSucceeded();
            metrics.outcome("sent");
            metrics.latency(receipt.latencyMs());
        } catch (TelegramSendException failure) {
            Instant failedAt = clock.instant();
            long latencyMs = nonNegativeMillis(startedAt, failedAt);
            if (failure.getKind() == TelegramSendFailureKind.AUTHENTICATION) {
                circuitBreaker.authenticationFailed(failedAt);
            }
            Instant nextAttemptAt = retryAt(delivery, failure, failedAt);
            String outcome = persistence.completeFailure(
                    delivery,
                    failure,
                    nextAttemptAt,
                    latencyMs,
                    failedAt
            );
            metrics.outcome(outcome.toLowerCase(java.util.Locale.ROOT));
            metrics.latency(latencyMs);
        }
    }

    private Instant retryAt(
            PreparedTelegramDelivery delivery,
            TelegramSendException failure,
            Instant now
    ) {
        if (failure.getKind() == TelegramSendFailureKind.AUTHENTICATION) {
            return now.plus(properties.deliveryRetryMaxDelay());
        }
        if (failure.getKind() != TelegramSendFailureKind.RATE_LIMITED
                && failure.getKind() != TelegramSendFailureKind.TRANSIENT_PROVIDER) {
            return null;
        }
        int exponent = Math.min(delivery.attemptNumber() - 1, 20);
        Duration initial = properties.deliveryRetryInitialDelay();
        Duration maximum = properties.deliveryRetryMaxDelay();
        long multiplied;
        try {
            multiplied = Math.multiplyExact(initial.toMillis(), 1L << exponent);
        } catch (ArithmeticException exception) {
            multiplied = maximum.toMillis();
        }
        long capped = Math.min(multiplied, maximum.toMillis());
        long jitterRange = Math.max(1, capped / 4);
        long seed = delivery.deliveryId().getMostSignificantBits()
                ^ delivery.deliveryId().getLeastSignificantBits()
                ^ delivery.attemptNumber();
        long jitter = Math.floorMod(seed, jitterRange);
        Instant calculated = now.plusMillis(capped + jitter);
        Instant provider = failure.getRetryAfterAt();
        return provider != null && provider.isAfter(calculated) ? provider : calculated;
    }

    private Instant earlier(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    private long nonNegativeMillis(Instant start, Instant end) {
        return Math.max(0, Duration.between(start, end).toMillis());
    }
}
