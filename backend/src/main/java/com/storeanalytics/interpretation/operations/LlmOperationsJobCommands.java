package com.storeanalytics.interpretation.operations;

import com.storeanalytics.interpretation.generation.LlmAnalysisEnqueueResult;
import com.storeanalytics.interpretation.generation.LlmAnalysisJob;
import com.storeanalytics.interpretation.generation.LlmAnalysisJobCoordinator;
import com.storeanalytics.interpretation.generation.LlmAnalysisJobStore;
import com.storeanalytics.interpretation.generation.LlmAnalysisRequestFactory;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class LlmOperationsJobCommands {

    private final LlmOperationsControlStore controlStore;
    private final LlmAnalysisJobStore jobStore;
    private final LlmAnalysisRequestFactory requestFactory;
    private final LlmAnalysisJobCoordinator coordinator;
    private final Clock clock;

    public LlmOperationsJobCommands(
            LlmOperationsControlStore controlStore,
            LlmAnalysisJobStore jobStore,
            LlmAnalysisRequestFactory requestFactory,
            LlmAnalysisJobCoordinator coordinator,
            Clock clock
    ) {
        this.controlStore = controlStore;
        this.jobStore = jobStore;
        this.requestFactory = requestFactory;
        this.coordinator = coordinator;
        this.clock = clock;
    }

    public RegenerationResult regenerate(UUID snapshotId, UUID actorId) {
        LlmOperationsControlStore.RegenerationTarget target =
                controlStore.lockRegenerationTarget(snapshotId);
        if (target.hasNewerSnapshot()) {
            throw new LlmOperationsConflictException(
                    "Only the latest snapshot revision can be regenerated"
            );
        }
        if (target.hasActiveJob()) {
            throw new LlmOperationsConflictException(
                    "An active generation job already exists for this snapshot"
            );
        }
        Instant now = clock.instant();
        LlmAnalysisEnqueueResult result = jobStore.enqueue(
                requestFactory.manual(
                        target.snapshot(),
                        target.nextGenerationRevision(),
                        actorId,
                        now
                ),
                now
        );
        return new RegenerationResult(result.job(), target.snapshot().storeId());
    }

    public CancellationResult cancel(UUID jobId) {
        LlmOperationsControlStore.JobContext context = controlStore.jobContext(jobId);
        LlmAnalysisJob before = jobStore.findById(jobId).orElseThrow(
                () -> new LlmOperationsNotFoundException("LLM analysis job was not found")
        );
        return new CancellationResult(
                before,
                coordinator.cancel(jobId),
                context.storeId()
        );
    }

    public record RegenerationResult(LlmAnalysisJob job, UUID storeId) {
    }

    public record CancellationResult(
            LlmAnalysisJob before,
            LlmAnalysisJob after,
            UUID storeId
    ) {
    }
}
