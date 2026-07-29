package com.storeanalytics.maintenance;

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
        prefix = "app.maintenance.retention",
        name = "scheduling-enabled",
        havingValue = "true",
        matchIfMissing = true
)
class DataRetentionScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            DataRetentionScheduler.class
    );

    private final DataRetentionService retentionService;
    private final DataRetentionMetrics metrics;

    DataRetentionScheduler(
            DataRetentionService retentionService,
            DataRetentionMetrics metrics
    ) {
        this.retentionService = retentionService;
        this.metrics = metrics;
    }

    @Scheduled(
            cron = "${app.maintenance.retention.cron:0 30 3 * * *}",
            zone = "${app.maintenance.retention.zone:Europe/Kaliningrad}",
            scheduler = BackgroundSchedulingConfiguration.RETENTION_SCHEDULER
    )
    void maintain() {
        try {
            DataRetentionRunResult result = metrics.record(retentionService::run);
            if (!result.lockAcquired()) {
                LOGGER.info("Technical data retention skipped because another instance owns the lock");
                return;
            }
            LOGGER.info(
                    "Technical data retention completed runId={} dryRun={} "
                            + "candidates={} affected={} remainingCandidates={}",
                    result.runId(),
                    result.dryRun(),
                    result.candidates(),
                    result.affected(),
                    result.remainingCandidates()
            );
        } catch (RuntimeException exception) {
            LOGGER.error("Technical data retention maintenance failed", exception);
        }
    }
}
