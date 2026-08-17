package com.storeanalytics.interpretation.generation;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Persistence boundary for the provider-call orchestration step.
 */
@Component
public class LlmProviderCallPersistence {

    private final LlmAnalysisAttemptStore attemptStore;
    private final LlmAnalysisPhaseTransitionStore transitionStore;
    private final LlmProviderFailureTransitionStore failureTransitionStore;
    private final LlmPreflightFailureTransitionStore preflightFailureStore;

    public LlmProviderCallPersistence(
            LlmAnalysisAttemptStore attemptStore,
            LlmAnalysisPhaseTransitionStore transitionStore,
            LlmProviderFailureTransitionStore failureTransitionStore,
            LlmPreflightFailureTransitionStore preflightFailureStore
    ) {
        this.attemptStore = attemptStore;
        this.transitionStore = transitionStore;
        this.failureTransitionStore = failureTransitionStore;
        this.preflightFailureStore = preflightFailureStore;
    }

    public LlmAnalysisJob recordPreflightRejection(
            UUID jobId,
            String owner,
            String errorCode,
            String safeSummary,
            Instant now
    ) {
        return preflightFailureStore.recordRejection(
                jobId, owner, errorCode, safeSummary, now
        );
    }

    public LlmAnalysisAttempt start(
            UUID jobId,
            String owner,
            LlmAnalysisAttemptType attemptType,
            String requestHash,
            String providerInputBody,
            Instant now
    ) {
        return attemptStore.startProviderCall(
                jobId, owner, attemptType, requestHash, providerInputBody, now
        );
    }

    public void recordResponse(
            UUID attemptId,
            String owner,
            LlmProviderResponseReceipt response,
            Instant now
    ) {
        attemptStore.recordProviderResponse(attemptId, owner, response, now);
    }

    public LlmAnalysisJob releaseForValidation(
            UUID jobId,
            String owner,
            Instant now
    ) {
        return transitionStore.releaseForValidation(jobId, owner, now);
    }

    public LlmAnalysisJob recordFailure(
            UUID jobId,
            UUID attemptId,
            String owner,
            LlmProviderException failure,
            Duration fallbackRetryDelay,
            Instant now
    ) {
        return failureTransitionStore.recordFailure(
                jobId,
                attemptId,
                owner,
                failure,
                fallbackRetryDelay,
                now
        );
    }
}
