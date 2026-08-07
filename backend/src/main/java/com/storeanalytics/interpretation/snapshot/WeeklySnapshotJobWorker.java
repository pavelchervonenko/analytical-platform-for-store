package com.storeanalytics.interpretation.snapshot;

import com.storeanalytics.common.config.ApplicationRole;
import com.storeanalytics.common.config.ConditionalOnApplicationRole;
import com.storeanalytics.interpretation.config.WeeklySnapshotSchedulingConfiguration;
import com.storeanalytics.interpretation.config.WeeklySnapshotWorkerProperties;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnApplicationRole({ApplicationRole.WORKER, ApplicationRole.COMBINED})
@ConditionalOnProperty(
        prefix = "app.interpretation.snapshot-worker",
        name = "enabled",
        havingValue = "true"
)
public class WeeklySnapshotJobWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            WeeklySnapshotJobWorker.class
    );

    private final String workerId = UUID.randomUUID().toString();
    private final WeeklySnapshotJobCoordinator coordinator;
    private final WeeklySnapshotWorkerProperties properties;

    public WeeklySnapshotJobWorker(
            WeeklySnapshotJobCoordinator coordinator,
            WeeklySnapshotWorkerProperties properties
    ) {
        this.coordinator = coordinator;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${app.interpretation.snapshot-worker.worker-delay:5s}",
            scheduler = WeeklySnapshotSchedulingConfiguration.SNAPSHOT_WORKER_SCHEDULER
    )
    public void processNext() {
        coordinator.runNext(
                workerId,
                properties.leaseDuration(),
                properties.retryInitialDelay(),
                properties.retryMaxDelay()
        );
    }

    @Scheduled(
            fixedDelayString = "${app.interpretation.snapshot-worker.heartbeat-interval:1m}",
            scheduler = WeeklySnapshotSchedulingConfiguration.SNAPSHOT_HEARTBEAT_SCHEDULER
    )
    public void heartbeat() {
        try {
            coordinator.heartbeatOwned(workerId, properties.leaseDuration());
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to renew weekly snapshot worker lease", exception);
        }
    }
}
