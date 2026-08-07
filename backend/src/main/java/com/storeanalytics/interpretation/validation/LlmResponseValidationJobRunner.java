package com.storeanalytics.interpretation.validation;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import com.storeanalytics.interpretation.generation.LlmAnalysisJob;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;

@Service
public class LlmResponseValidationJobRunner {

    private final AtomicReference<ActiveJob> activeJob = new AtomicReference<>();
    private final LlmResponseValidationCoordinator coordinator;
    private final LlmResponseValidationService validationService;

    public LlmResponseValidationJobRunner(
            LlmResponseValidationCoordinator coordinator,
            LlmResponseValidationService validationService
    ) {
        this.coordinator = coordinator;
        this.validationService = validationService;
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
            throw new IllegalStateException("LLM validation worker is already processing a job");
        }
        try {
            return Optional.of(validationService.execute(job, workerOwner));
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
