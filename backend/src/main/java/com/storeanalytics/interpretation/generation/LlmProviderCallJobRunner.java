package com.storeanalytics.interpretation.generation;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;

@Service
public class LlmProviderCallJobRunner {

    private final AtomicReference<ActiveJob> activeJob = new AtomicReference<>();
    private final LlmProviderCallCoordinator coordinator;
    private final LlmProviderCallExecutionService executionService;

    public LlmProviderCallJobRunner(
            LlmProviderCallCoordinator coordinator,
            LlmProviderCallExecutionService executionService
    ) {
        this.coordinator = coordinator;
        this.executionService = executionService;
    }

    public Optional<LlmAnalysisJob> runNext(
            String owner,
            Duration leaseDuration,
            Duration recoveryDelay
    ) {
        String workerOwner = requireText(owner, "owner");
        Optional<LlmAnalysisJob> claimed = coordinator.claimNext(
                workerOwner,
                requireNonNull(leaseDuration, "leaseDuration"),
                requireNonNull(recoveryDelay, "recoveryDelay")
        );
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        LlmAnalysisJob job = claimed.get();
        ActiveJob active = new ActiveJob(job.id(), workerOwner);
        if (!activeJob.compareAndSet(null, active)) {
            throw new IllegalStateException("LLM provider worker is already processing a job");
        }
        try {
            return Optional.of(executionService.execute(job, workerOwner));
        } finally {
            activeJob.compareAndSet(active, null);
        }
    }

    public Optional<LlmAnalysisJob> heartbeatCurrent(Duration leaseDuration) {
        ActiveJob active = activeJob.get();
        if (active == null) {
            return Optional.empty();
        }
        return Optional.of(coordinator.heartbeat(
                active.jobId(),
                active.owner(),
                requireNonNull(leaseDuration, "leaseDuration")
        ));
    }

    private record ActiveJob(UUID jobId, String owner) {
    }
}
