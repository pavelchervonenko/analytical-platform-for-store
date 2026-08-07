package com.storeanalytics.interpretation.snapshot;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class WeeklySnapshotOperatorSignals {

    static final String EVENTS_METRIC =
            "storeanalytics.interpretation.snapshot.job.events";

    private static final Logger LOGGER = LoggerFactory.getLogger(
            WeeklySnapshotOperatorSignals.class
    );

    private final Counter terminalFailures;
    private final Counter expiredLeaseRecoveries;

    public WeeklySnapshotOperatorSignals(MeterRegistry registry) {
        MeterRegistry meterRegistry = requireNonNull(registry, "registry");
        terminalFailures = counter(meterRegistry, "terminal_failure");
        expiredLeaseRecoveries = counter(
                meterRegistry,
                "expired_lease_recovered"
        );
    }

    public void terminalFailure(WeeklySnapshotJob job) {
        WeeklySnapshotJob failed = requireNonNull(job, "job");
        terminalFailures.increment();
        LOGGER.atError()
                .addKeyValue("event_code", "weekly_snapshot_job_terminal_failure")
                .addKeyValue("job_id", failed.id())
                .addKeyValue("store_id", failed.storeId())
                .addKeyValue("job_type", failed.jobType())
                .addKeyValue("attempt_count", failed.attemptCount())
                .addKeyValue("max_attempts", failed.maxAttempts())
                .addKeyValue("error_code", failed.errorCode())
                .log("Weekly snapshot job reached terminal failure");
    }

    public void expiredLeaseRecovered(WeeklySnapshotJob job) {
        WeeklySnapshotJob recovered = requireNonNull(job, "job");
        expiredLeaseRecoveries.increment();
        if (recovered.status() == WeeklySnapshotJobStatus.FAILED) {
            terminalFailures.increment();
            LOGGER.atError()
                    .addKeyValue("event_code", "weekly_snapshot_lease_terminal_failure")
                    .addKeyValue("job_id", recovered.id())
                    .addKeyValue("store_id", recovered.storeId())
                    .addKeyValue("attempt_count", recovered.attemptCount())
                    .addKeyValue("max_attempts", recovered.maxAttempts())
                    .log("Expired weekly snapshot lease exhausted retry attempts");
            return;
        }
        LOGGER.atWarn()
                .addKeyValue("event_code", "weekly_snapshot_lease_recovered")
                .addKeyValue("job_id", recovered.id())
                .addKeyValue("store_id", recovered.storeId())
                .addKeyValue("recovered_status", recovered.status())
                .addKeyValue("attempt_count", recovered.attemptCount())
                .addKeyValue("max_attempts", recovered.maxAttempts())
                .log("Recovered expired weekly snapshot worker lease");
    }

    private Counter counter(MeterRegistry registry, String event) {
        return Counter.builder(EVENTS_METRIC)
                .description("Weekly snapshot operational transition events")
                .tag("event", event)
                .register(registry);
    }
}
