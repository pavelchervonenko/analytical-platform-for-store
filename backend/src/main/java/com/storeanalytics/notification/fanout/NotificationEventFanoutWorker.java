package com.storeanalytics.notification.fanout;

import com.storeanalytics.common.config.ApplicationRole;
import com.storeanalytics.common.config.ConditionalOnApplicationRole;
import com.storeanalytics.notification.config.NotificationFanoutSchedulingConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnApplicationRole({ApplicationRole.WORKER, ApplicationRole.COMBINED})
@ConditionalOnProperty(
        prefix = "app.notification.telegram",
        name = {"enabled", "fanout-enabled"},
        havingValue = "true"
)
public class NotificationEventFanoutWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            NotificationEventFanoutWorker.class
    );

    private final NotificationEventFanoutService service;
    private final DailyNotificationEventFanoutService dailyService;

    public NotificationEventFanoutWorker(
            NotificationEventFanoutService service,
            DailyNotificationEventFanoutService dailyService
    ) {
        this.service = service;
        this.dailyService = dailyService;
    }

    @Scheduled(
            fixedDelayString = "${app.notification.telegram.fanout-delay:5s}",
            scheduler = NotificationFanoutSchedulingConfiguration
                    .NOTIFICATION_FANOUT_SCHEDULER
    )
    public void processNext() {
        try {
            if (service.processNext().isEmpty()) {
                dailyService.processNext();
            }
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "Notification fanout step failed; failure_type={}",
                    exception.getClass().getSimpleName()
            );
        }
    }
}
