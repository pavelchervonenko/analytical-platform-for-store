package com.storeanalytics.interpretation.snapshot;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class WeeklySnapshotJobCoordinator {

    private final WeeklySnapshotJobLifecycleStore lifecycleStore;
    private final WeeklySnapshotJobControlStore controlStore;
    private final WeeklySnapshotJobRunner runner;
    private final WeeklySnapshotOperatorSignals operatorSignals;
    private final Clock clock;

    public WeeklySnapshotJobCoordinator(
            WeeklySnapshotJobLifecycleStore lifecycleStore,
            WeeklySnapshotJobControlStore controlStore,
            WeeklySnapshotJobRunner runner,
            WeeklySnapshotOperatorSignals operatorSignals,
            Clock clock
    ) {
        this.lifecycleStore = lifecycleStore;
        this.controlStore = controlStore;
        this.runner = runner;
        this.operatorSignals = operatorSignals;
        this.clock = clock;
    }

    public Optional<WeeklySnapshotJob> runNext(
            String owner,
            Duration leaseDuration,
            Duration retryInitialDelay,
            Duration retryMaxDelay
    ) {
        Duration recoveryDelay = positive(retryInitialDelay, "retryInitialDelay");
        Instant now = clock.instant();
        lifecycleStore.recoverOneExpiredLease(now.plus(recoveryDelay), now)
                .ifPresent(operatorSignals::expiredLeaseRecovered);
        return runner.runNext(
                owner,
                leaseDuration,
                recoveryDelay,
                retryMaxDelay
        );
    }

    public Optional<UUID> heartbeatOwned(
            String owner,
            Duration leaseDuration
    ) {
        return controlStore.heartbeatOwned(owner, leaseDuration, clock.instant());
    }

    public WeeklySnapshotJob heartbeat(
            UUID jobId,
            String owner,
            Duration leaseDuration
    ) {
        return lifecycleStore.heartbeat(
                jobId,
                owner,
                leaseDuration,
                clock.instant()
        );
    }

    public WeeklySnapshotJob cancel(UUID jobId) {
        return lifecycleStore.requestCancellation(jobId, clock.instant());
    }

    private Duration positive(Duration value, String field) {
        Duration duration = requireNonNull(value, field);
        require(!duration.isZero() && !duration.isNegative(), field + " must be positive");
        return duration;
    }
}
