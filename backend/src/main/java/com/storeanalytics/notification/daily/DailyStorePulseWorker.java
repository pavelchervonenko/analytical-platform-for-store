package com.storeanalytics.notification.daily;

import com.storeanalytics.common.config.ApplicationRole;
import com.storeanalytics.common.config.ConditionalOnApplicationRole;
import com.storeanalytics.notification.config.DailyStorePulseSchedulingConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnApplicationRole({ApplicationRole.WORKER, ApplicationRole.COMBINED})
@ConditionalOnProperty(
        prefix = "app.notification.daily-pulse",
        name = "enabled",
        havingValue = "true"
)
public class DailyStorePulseWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            DailyStorePulseWorker.class
    );

    private final DailyStorePulsePlanner planner;
    private final DailyStorePulseMetrics metrics;

    public DailyStorePulseWorker(
            DailyStorePulsePlanner planner,
            DailyStorePulseMetrics metrics
    ) {
        this.planner = planner;
        this.metrics = metrics;
    }

    @Scheduled(
            fixedDelayString = "${app.notification.daily-pulse.planner-delay:5m}",
            scheduler = DailyStorePulseSchedulingConfiguration
                    .DAILY_STORE_PULSE_SCHEDULER
    )
    public void plan() {
        try {
            planner.plan();
        } catch (RuntimeException exception) {
            metrics.failed();
            LOGGER.error(
                    "Daily store pulse planning failed; failure_type={}",
                    exception.getClass().getSimpleName()
            );
        }
    }
}
