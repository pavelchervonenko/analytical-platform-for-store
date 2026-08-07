package com.storeanalytics.interpretation.generation;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class LlmAnalysisJobCoordinator {

    private final LlmAnalysisJobClaimStore claimStore;
    private final LlmAnalysisJobLifecycleStore lifecycleStore;
    private final LlmAnalysisOperatorSignals operatorSignals;
    private final Clock clock;

    public LlmAnalysisJobCoordinator(
            LlmAnalysisJobClaimStore claimStore,
            LlmAnalysisJobLifecycleStore lifecycleStore,
            LlmAnalysisOperatorSignals operatorSignals,
            Clock clock
    ) {
        this.claimStore = claimStore;
        this.lifecycleStore = lifecycleStore;
        this.operatorSignals = operatorSignals;
        this.clock = clock;
    }

    public Optional<LlmAnalysisJob> claimNext(
            String owner,
            Duration leaseDuration,
            Duration recoveryDelay
    ) {
        Duration delay = positive(recoveryDelay, "recoveryDelay");
        Instant now = clock.instant();
        lifecycleStore.expireOnePastDeadline(now)
                .ifPresent(operatorSignals::deadlineExceeded);
        lifecycleStore.recoverOneExpiredLease(now.plus(delay), now)
                .ifPresent(job -> {
                    operatorSignals.recoveredLease(job);
                    if (LlmAnalysisJobLifecycleStore.DEADLINE_EXCEEDED.equals(
                            job.terminalReasonCode()
                    )) {
                        operatorSignals.deadlineExceeded(job);
                    }
                });
        return claimStore.claimNext(owner, leaseDuration, now);
    }

    public LlmAnalysisJob heartbeat(
            UUID jobId,
            String owner,
            Duration leaseDuration
    ) {
        return lifecycleStore.heartbeat(jobId, owner, leaseDuration, clock.instant());
    }

    public LlmAnalysisJob cancel(UUID jobId) {
        return lifecycleStore.requestCancellation(jobId, clock.instant());
    }

    private Duration positive(Duration value, String field) {
        Duration duration = requireNonNull(value, field);
        require(!duration.isZero() && !duration.isNegative(), field + " must be positive");
        return duration;
    }
}
