package com.storeanalytics.interpretation.generation;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import com.storeanalytics.interpretation.config.LlmAnalysisWorkerProperties;
import java.time.Clock;
import org.springframework.stereotype.Service;

@Service
public class LlmProviderCallExecutionService {

    private final LlmProviderRegistry providerRegistry;
    private final LlmProviderRequestFactory requestFactory;
    private final LlmProviderBudgetGuard budgetGuard;
    private final LlmProviderCallPersistence persistence;
    private final LlmAnalysisWorkerProperties properties;
    private final LlmProviderOrchestrationMetrics metrics;
    private final Clock clock;

    public LlmProviderCallExecutionService(
            LlmProviderRegistry providerRegistry,
            LlmProviderRequestFactory requestFactory,
            LlmProviderBudgetGuard budgetGuard,
            LlmProviderCallPersistence persistence,
            LlmAnalysisWorkerProperties properties,
            LlmProviderOrchestrationMetrics metrics,
            Clock clock
    ) {
        this.providerRegistry = providerRegistry;
        this.requestFactory = requestFactory;
        this.budgetGuard = budgetGuard;
        this.persistence = persistence;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    public LlmAnalysisJob execute(LlmAnalysisJob job, String owner) {
        LlmAnalysisJob claimed = requireNonNull(job, "job");
        String leaseOwner = requireText(owner, "owner");
        require(claimed.status() == LlmAnalysisJobStatus.RUNNING,
                "LLM job must be RUNNING");
        require(leaseOwner.equals(claimed.leaseOwner()),
                "LLM job lease is owned elsewhere");
        require(claimed.phase() == LlmAnalysisPhase.PREPARE
                        || claimed.phase() == LlmAnalysisPhase.CALL_PROVIDER
                        || claimed.phase() == LlmAnalysisPhase.VALIDATE_RESPONSE,
                "provider-call worker received unsupported phase");

        LlmProviderClient provider = providerRegistry.requireProvider(
                claimed.providerCode()
        );
        PreparedLlmProviderRequest prepared = requestFactory.prepare(
                claimed,
                clock.instant(),
                properties.providerCallTimeout()
        );
        try {
            LlmProviderPreflight preflight = provider.preflight(prepared.request());
            budgetGuard.validate(prepared.request(), preflight);
        } catch (LlmProviderException failure) {
            String reason = "PROVIDER_" + failure.failureCode();
            metrics.preflightRejected(reason);
            return persistence.recordPreflightRejection(
                    claimed.id(),
                    leaseOwner,
                    "LLM_" + reason,
                    failure.getMessage(),
                    clock.instant()
            );
        } catch (LlmProviderPreflightException failure) {
            String reason = failure.getKind().name();
            metrics.preflightRejected(reason);
            return persistence.recordPreflightRejection(
                    claimed.id(),
                    leaseOwner,
                    "LLM_PREFLIGHT_" + reason,
                    failure.getMessage(),
                    clock.instant()
            );
        }

        LlmAnalysisAttemptType attemptType = switch (claimed.phase()) {
            case PREPARE -> LlmAnalysisAttemptType.INITIAL;
            case CALL_PROVIDER -> LlmAnalysisAttemptType.TRANSPORT_RETRY;
            case VALIDATE_RESPONSE -> LlmAnalysisAttemptType.VALIDATION_RETRY;
            case PUBLISH -> throw new IllegalStateException(
                    "PUBLISH phase cannot call the LLM provider"
            );
        };
        LlmAnalysisAttempt attempt = persistence.start(
                claimed.id(),
                leaseOwner,
                attemptType,
                prepared.requestHash(),
                prepared.request().inputJson(),
                clock.instant()
        );
        LlmProviderResponseReceipt response;
        try {
            response = provider.generate(prepared.request());
        } catch (LlmProviderException failure) {
            return persistence.recordFailure(
                    claimed.id(),
                    attempt.id(),
                    leaseOwner,
                    failure,
                    properties.recoveryDelay(),
                    clock.instant()
            );
        }
        persistence.recordResponse(
                attempt.id(),
                leaseOwner,
                response,
                clock.instant()
        );
        return persistence.releaseForValidation(
                claimed.id(),
                leaseOwner,
                clock.instant()
        );
    }

}
