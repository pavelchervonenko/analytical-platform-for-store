package com.storeanalytics.sync.service;

import com.storeanalytics.common.config.ApplicationRole;
import com.storeanalytics.common.config.BackgroundSchedulingConfiguration;
import com.storeanalytics.common.config.ConditionalOnApplicationRole;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnApplicationRole({ApplicationRole.WORKER, ApplicationRole.COMBINED})
@ConditionalOnProperty(prefix = "app.sync.historical-sales", name = "enabled", havingValue = "true")
public class ScheduledHistoricalSalesRefresh {
    private final HistoricalSalesRefreshService service;

    public ScheduledHistoricalSalesRefresh(HistoricalSalesRefreshService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${app.sync.historical-sales.enqueue-delay:1m}",
            initialDelayString = "${app.sync.historical-sales.initial-delay:1m}",
            scheduler = BackgroundSchedulingConfiguration.SYNC_CONTROL_SCHEDULER)
    public void enqueue() {
        service.enqueue();
    }
}
