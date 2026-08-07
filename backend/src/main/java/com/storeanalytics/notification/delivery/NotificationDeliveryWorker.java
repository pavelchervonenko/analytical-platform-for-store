package com.storeanalytics.notification.delivery;

import com.storeanalytics.common.config.ApplicationRole;
import com.storeanalytics.common.config.ConditionalOnApplicationRole;
import com.storeanalytics.notification.config.NotificationDeliverySchedulingConfiguration;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnApplicationRole({ApplicationRole.WORKER, ApplicationRole.COMBINED})
@ConditionalOnProperty(
        prefix = "app.notification.telegram",
        name = {"enabled", "delivery-enabled"},
        havingValue = "true"
)
public class NotificationDeliveryWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            NotificationDeliveryWorker.class
    );

    private final NotificationDeliveryPersistence persistence;
    private final NotificationDeliveryExecutionService executionService;
    private final NotificationDeliveryMetrics metrics;
    private final Clock clock;
    private final String owner = "telegram-delivery-" + UUID.randomUUID();

    public NotificationDeliveryWorker(
            NotificationDeliveryPersistence persistence,
            NotificationDeliveryExecutionService executionService,
            NotificationDeliveryMetrics metrics,
            Clock clock
    ) {
        this.persistence = persistence;
        this.executionService = executionService;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${app.notification.telegram.delivery-delay:5s}",
            scheduler = NotificationDeliverySchedulingConfiguration
                    .NOTIFICATION_DELIVERY_SCHEDULER
    )
    public void processNext() {
        try {
            NotificationDeliveryRecoveryOutcome recovery =
                    persistence.recoverOneExpiredLease(clock.instant());
            if (recovery != NotificationDeliveryRecoveryOutcome.NONE) {
                metrics.outcome("recovery_" + recovery.name().toLowerCase(
                        java.util.Locale.ROOT
                ));
            }
            executionService.processNext(owner);
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "Notification delivery step failed; failure_type={}",
                    exception.getClass().getSimpleName()
            );
        }
    }
}
