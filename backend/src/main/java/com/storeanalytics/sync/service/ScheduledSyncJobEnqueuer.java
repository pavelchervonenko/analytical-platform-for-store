package com.storeanalytics.sync.service;

import com.storeanalytics.common.config.ApplicationRole;
import com.storeanalytics.common.config.BackgroundSchedulingConfiguration;
import com.storeanalytics.common.config.ConditionalOnApplicationRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnApplicationRole({ApplicationRole.WORKER, ApplicationRole.COMBINED})
@ConditionalOnProperty(
        prefix = "app.sync",
        name = "schedule-enabled",
        havingValue = "true"
)
public class ScheduledSyncJobEnqueuer {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            ScheduledSyncJobEnqueuer.class
    );

    private final SyncJobService jobService;

    public ScheduledSyncJobEnqueuer(SyncJobService jobService) {
        this.jobService = jobService;
    }

    @Scheduled(
            cron = "${app.sync.schedule-cron:0 15 3-8 * * *}",
            zone = "${app.sync.schedule-zone:Europe/Kaliningrad}",
            scheduler = BackgroundSchedulingConfiguration.SYNC_CONTROL_SCHEDULER
    )
    public void enqueueIncrementalJob() {
        jobService.createScheduledIncremental().ifPresent(job -> LOGGER.info(
                "Created scheduled synchronization job {}",
                job.id()
        ));
    }
}
