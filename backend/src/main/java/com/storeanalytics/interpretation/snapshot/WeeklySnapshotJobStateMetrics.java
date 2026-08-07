package com.storeanalytics.interpretation.snapshot;

import com.storeanalytics.common.config.ApplicationRole;
import com.storeanalytics.common.config.BackgroundSchedulingConfiguration;
import com.storeanalytics.common.config.ConditionalOnApplicationRole;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnApplicationRole({ApplicationRole.WORKER, ApplicationRole.COMBINED})
public class WeeklySnapshotJobStateMetrics implements MeterBinder {

    static final String JOBS_METRIC = "storeanalytics.interpretation.snapshot.jobs";

    private static final Logger LOGGER = LoggerFactory.getLogger(
            WeeklySnapshotJobStateMetrics.class
    );

    private final WeeklySnapshotJobLifecycleStore lifecycleStore;
    private final Clock clock;
    private final AtomicReference<JobCounts> counts = new AtomicReference<>(
            JobCounts.unknown()
    );

    public WeeklySnapshotJobStateMetrics(
            WeeklySnapshotJobLifecycleStore lifecycleStore,
            Clock clock
    ) {
        this.lifecycleStore = lifecycleStore;
        this.clock = clock;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        gauge(registry, "pending", value -> value.pending());
        gauge(registry, "running", value -> value.running());
        gauge(registry, "failed", value -> value.failed());
        gauge(registry, "retrying", value -> value.retrying());
        gauge(registry, "expired_lease", value -> value.expiredLease());
    }

    @Scheduled(
            initialDelayString = "${app.observability.state-initial-delay:30s}",
            fixedDelayString = "${app.observability.state-refresh-delay:1m}",
            scheduler = BackgroundSchedulingConfiguration.METRICS_SCHEDULER
    )
    public void refresh() {
        try {
            counts.set(new JobCounts(
                    lifecycleStore.countByStatus(WeeklySnapshotJobStatus.PENDING),
                    lifecycleStore.countByStatus(WeeklySnapshotJobStatus.RUNNING),
                    lifecycleStore.countByStatus(WeeklySnapshotJobStatus.FAILED),
                    lifecycleStore.countByStatus(WeeklySnapshotJobStatus.WAITING_RETRY),
                    lifecycleStore.countExpiredLeases(clock.instant())
            ));
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to refresh weekly snapshot job metrics", exception);
        }
    }

    private void gauge(
            MeterRegistry registry,
            String status,
            java.util.function.ToDoubleFunction<JobCounts> value
    ) {
        Gauge.builder(JOBS_METRIC, counts, state -> value.applyAsDouble(state.get()))
                .description("Current weekly snapshot jobs by operational status")
                .tag("status", status)
                .register(registry);
    }

    private record JobCounts(
            double pending,
            double running,
            double failed,
            double retrying,
            double expiredLease
    ) {

        private static JobCounts unknown() {
            return new JobCounts(
                    Double.NaN,
                    Double.NaN,
                    Double.NaN,
                    Double.NaN,
                    Double.NaN
            );
        }
    }
}
