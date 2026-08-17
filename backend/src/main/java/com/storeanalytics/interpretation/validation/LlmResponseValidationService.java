package com.storeanalytics.interpretation.validation;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput;
import com.storeanalytics.interpretation.generation.LlmAnalysisAttempt;
import com.storeanalytics.interpretation.generation.LlmAnalysisAttemptStatus;
import com.storeanalytics.interpretation.generation.LlmAnalysisAttemptStore;
import com.storeanalytics.interpretation.generation.LlmAnalysisJob;
import com.storeanalytics.interpretation.generation.LlmAnalysisJobStatus;
import com.storeanalytics.interpretation.generation.LlmAnalysisPhase;
import com.storeanalytics.interpretation.snapshot.PersistedWeeklySnapshot;
import com.storeanalytics.interpretation.snapshot.WeeklySnapshotStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class LlmResponseValidationService {

    private final WeeklySnapshotStore snapshotStore;
    private final PersistedWeeklyInterpretationInputFactory inputFactory;
    private final LlmAnalysisAttemptStore attemptStore;
    private final VersionedWeeklyInterpretationResponseValidator validator;
    private final LlmResponseValidationTransitionStore transitionStore;
    private final LlmResponseValidationMetrics metrics;
    private final Clock clock;

    public LlmResponseValidationService(
            WeeklySnapshotStore snapshotStore,
            PersistedWeeklyInterpretationInputFactory inputFactory,
            LlmAnalysisAttemptStore attemptStore,
            VersionedWeeklyInterpretationResponseValidator validator,
            LlmResponseValidationTransitionStore transitionStore,
            LlmResponseValidationMetrics metrics,
            Clock clock
    ) {
        this.snapshotStore = snapshotStore;
        this.inputFactory = inputFactory;
        this.attemptStore = attemptStore;
        this.validator = validator;
        this.transitionStore = transitionStore;
        this.metrics = metrics;
        this.clock = clock;
    }

    public LlmAnalysisJob execute(LlmAnalysisJob job, String owner) {
        LlmAnalysisJob claimed = requireNonNull(job, "job");
        String leaseOwner = requireText(owner, "owner");
        require(claimed.status() == LlmAnalysisJobStatus.RUNNING,
                "LLM job must be RUNNING");
        require(claimed.phase() == LlmAnalysisPhase.VALIDATE_RESPONSE,
                "validation worker received unsupported phase");
        require(leaseOwner.equals(claimed.leaseOwner()),
                "LLM job lease is owned elsewhere");
        LlmAnalysisAttempt attempt = attemptStore.findOpenByJobId(claimed.id())
                .orElseThrow(() -> new IllegalStateException(
                        "Validation job has no open provider response"
                ));
        require(attempt.status() == LlmAnalysisAttemptStatus.RESPONSE_RECEIVED,
                "validation requires a persisted provider response");
        PersistedWeeklySnapshot snapshot = snapshotStore.findById(claimed.snapshotId())
                .orElseThrow(() -> new IllegalStateException(
                        "Validation job snapshot does not exist"
                ));
        WeeklyInterpretationInput input = inputFactory.create(attempt, snapshot);
        Instant startedAt = clock.instant();
        LlmResponseValidationResult result = validator.validate(
                claimed.contentSchemaVersion(),
                input,
                attempt.responseBody()
        );
        Instant completedAt = clock.instant();
        LlmAnalysisJob transitioned = transitionStore.complete(
                claimed.id(),
                attempt.id(),
                leaseOwner,
                result,
                completedAt
        );
        metrics.record(result.outcome(), Duration.between(startedAt, completedAt));
        return transitioned;
    }
}
