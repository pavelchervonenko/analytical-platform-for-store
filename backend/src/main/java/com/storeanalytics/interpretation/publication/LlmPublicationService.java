package com.storeanalytics.interpretation.publication;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import com.storeanalytics.interpretation.generation.LlmAnalysisAttempt;
import com.storeanalytics.interpretation.generation.LlmAnalysisAttemptStore;
import com.storeanalytics.interpretation.generation.LlmAnalysisJob;
import com.storeanalytics.interpretation.generation.LlmAnalysisJobStatus;
import com.storeanalytics.interpretation.generation.LlmAnalysisPhase;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class LlmPublicationService {

    private final LlmAnalysisAttemptStore attemptStore;
    private final WeeklyPublicationMaterialFactory materialFactory;
    private final LlmPublicationStore publicationStore;
    private final LlmPublicationMetrics metrics;
    private final Clock clock;

    public LlmPublicationService(
            LlmAnalysisAttemptStore attemptStore,
            WeeklyPublicationMaterialFactory materialFactory,
            LlmPublicationStore publicationStore,
            LlmPublicationMetrics metrics,
            Clock clock
    ) {
        this.attemptStore = attemptStore;
        this.materialFactory = materialFactory;
        this.publicationStore = publicationStore;
        this.metrics = metrics;
        this.clock = clock;
    }

    public LlmPublicationResult execute(LlmAnalysisJob job, String owner) {
        LlmAnalysisJob claimed = requireNonNull(job, "job");
        String leaseOwner = requireText(owner, "owner");
        require(claimed.status() == LlmAnalysisJobStatus.RUNNING,
                "publication job must be RUNNING");
        require(claimed.phase() == LlmAnalysisPhase.PUBLISH,
                "publication worker received unsupported phase");
        require(leaseOwner.equals(claimed.leaseOwner()),
                "publication lease is owned elsewhere");
        LlmAnalysisAttempt attempt = attemptStore.findSuccessfulByJobId(claimed.id())
                .orElseThrow(() -> new IllegalStateException(
                        "Publication job has no successful validated attempt"
                ));
        WeeklyPublicationMaterial material = materialFactory.create(attempt);
        Instant startedAt = clock.instant();
        LlmPublicationResult result = publicationStore.publish(
                claimed.id(),
                attempt.id(),
                leaseOwner,
                material,
                clock.instant()
        );
        metrics.published(Duration.between(startedAt, clock.instant()));
        return result;
    }
}
