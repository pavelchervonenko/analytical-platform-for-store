package com.storeanalytics.interpretation.publication;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.interpretation.generation.LlmAnalysisJob;
import com.storeanalytics.interpretation.generation.LlmAnalysisJobLifecycleStore;
import com.storeanalytics.interpretation.generation.LlmAnalysisOperatorSignals;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class LlmPublicationCoordinator {

    private final LlmPublicationClaimStore claimStore;
    private final LlmAnalysisJobLifecycleStore lifecycleStore;
    private final LlmAnalysisOperatorSignals operatorSignals;
    private final Clock clock;

    public LlmPublicationCoordinator(
            LlmPublicationClaimStore claimStore,
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
                    if ("LLM_GENERATION_DEADLINE_EXCEEDED".equals(
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

    private Duration positive(Duration value, String field) {
        Duration duration = requireNonNull(value, field);
        require(!duration.isZero() && !duration.isNegative(), field + " must be positive");
        return duration;
    }
}
