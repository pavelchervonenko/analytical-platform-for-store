package com.storeanalytics.interpretation.snapshot;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class WeeklySnapshotJobRunner {

    private final WeeklySnapshotJobStore jobStore;
    private final WeeklySnapshotJobExecutionService executionService;
    private final WeeklySnapshotJobFailureClassifier failureClassifier;
    private final WeeklySnapshotOperatorSignals operatorSignals;
    private final Clock clock;

    public WeeklySnapshotJobRunner(
            WeeklySnapshotJobStore jobStore,
            WeeklySnapshotJobExecutionService executionService,
            WeeklySnapshotJobFailureClassifier failureClassifier,
            WeeklySnapshotOperatorSignals operatorSignals,
            Clock clock
    ) {
        this.jobStore = jobStore;
        this.executionService = executionService;
        this.failureClassifier = failureClassifier;
        this.operatorSignals = operatorSignals;
        this.clock = clock;
    }

    public Optional<WeeklySnapshotJob> runNext(
            String owner,
            Duration leaseDuration,
            Duration retryInitialDelay,
            Duration retryMaxDelay
    ) {
        String workerOwner = requireText(owner, "owner");
        Duration lease = positive(leaseDuration, "leaseDuration");
        Duration initialDelay = positive(retryInitialDelay, "retryInitialDelay");
        Duration maxDelay = positive(retryMaxDelay, "retryMaxDelay");
        require(initialDelay.compareTo(maxDelay) <= 0,
                "retryInitialDelay must not exceed retryMaxDelay");

        Optional<WeeklySnapshotJob> claimed = jobStore.claimNext(
                workerOwner,
                lease,
                clock.instant()
        );
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        WeeklySnapshotJob job = claimed.get();
        try {
            return Optional.of(executionService.execute(job, workerOwner));
        } catch (RuntimeException exception) {
            WeeklySnapshotJobFailure failure = failureClassifier.classify(exception);
            Instant failedAt = clock.instant();
            Duration delay = retryDelay(job.attemptCount(), initialDelay, maxDelay);
            WeeklySnapshotJob result = jobStore.retryOrFail(
                    job.id(),
                    workerOwner,
                    failure.retryable(),
                    failure.errorCode(),
                    failure.safeSummary(),
                    failedAt.plus(delay),
                    failedAt
            );
            if (result.status() == WeeklySnapshotJobStatus.FAILED) {
                operatorSignals.terminalFailure(result);
            }
            return Optional.of(result);
        }
    }

    private Duration retryDelay(
            int attemptCount,
            Duration initialDelay,
            Duration maxDelay
    ) {
        int exponent = Math.min(Math.max(attemptCount - 1, 0), 20);
        Duration candidate;
        try {
            candidate = initialDelay.multipliedBy(1L << exponent);
        } catch (ArithmeticException exception) {
            candidate = maxDelay;
        }
        return candidate.compareTo(maxDelay) > 0 ? maxDelay : candidate;
    }

    private Duration positive(Duration value, String field) {
        Duration duration = requireNonNull(value, field);
        require(!duration.isZero() && !duration.isNegative(), field + " must be positive");
        return duration;
    }
}
