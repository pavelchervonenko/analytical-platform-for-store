package com.storeanalytics.interpretation.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeanalytics.interpretation.config.LlmAnalysisWorkerProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LlmProviderCallExecutionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-03T06:30:00Z");

    @Test
    void convertsTypedProviderFailureIntoImmediateDurableTransition() {
        UUID jobId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        LlmAnalysisJob claimed = mock(LlmAnalysisJob.class);
        when(claimed.id()).thenReturn(jobId);
        when(claimed.providerCode()).thenReturn("TEST");
        when(claimed.status()).thenReturn(LlmAnalysisJobStatus.RUNNING);
        when(claimed.phase()).thenReturn(LlmAnalysisPhase.PREPARE);
        when(claimed.leaseOwner()).thenReturn("provider-worker");
        LlmAnalysisJob transitioned = mock(LlmAnalysisJob.class);

        LlmAnalysisWorkerProperties properties = properties();
        LlmProviderRequest request = new LlmProviderRequest(
                jobId,
                "TEST",
                "test-model",
                "system",
                "{}",
                "{}",
                new BigDecimal("0.2"),
                1_000,
                NOW.plusSeconds(90)
        );
        PreparedLlmProviderRequest prepared = new PreparedLlmProviderRequest(
                request,
                "c".repeat(64)
        );
        LlmProviderRequestFactory requestFactory = mock(LlmProviderRequestFactory.class);
        when(requestFactory.prepare(
                claimed,
                NOW,
                properties.providerCallTimeout()
        )).thenReturn(prepared);

        TestProviderException failure = new TestProviderException();
        LlmProviderClient provider = new FailingProvider(failure);
        LlmAnalysisAttempt attempt = mock(LlmAnalysisAttempt.class);
        when(attempt.id()).thenReturn(attemptId);
        LlmProviderCallPersistence persistence = mock(
                LlmProviderCallPersistence.class
        );
        when(persistence.start(
                jobId,
                "provider-worker",
                LlmAnalysisAttemptType.INITIAL,
                prepared.requestHash(),
                request.inputJson(),
                NOW
        )).thenReturn(attempt);
        when(persistence.recordFailure(
                jobId,
                attemptId,
                "provider-worker",
                failure,
                properties.recoveryDelay(),
                NOW
        )).thenReturn(transitioned);
        LlmProviderCallExecutionService service =
                new LlmProviderCallExecutionService(
                        new LlmProviderRegistry(List.of(provider)),
                        requestFactory,
                        mock(LlmProviderBudgetGuard.class),
                        persistence,
                        properties,
                        mock(LlmProviderOrchestrationMetrics.class),
                        Clock.fixed(NOW, ZoneOffset.UTC)
                );

        LlmAnalysisJob result = service.execute(claimed, "provider-worker");

        assertThat(result).isSameAs(transitioned);
        verify(persistence).recordFailure(
                jobId,
                attemptId,
                "provider-worker",
                failure,
                properties.recoveryDelay(),
                NOW
        );
        verify(persistence, never()).recordResponse(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
        verify(persistence, never()).releaseForValidation(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void terminatesBudgetRejectionBeforeStartingProviderAttempt() {
        UUID jobId = UUID.randomUUID();
        LlmAnalysisJob claimed = mock(LlmAnalysisJob.class);
        when(claimed.id()).thenReturn(jobId);
        when(claimed.providerCode()).thenReturn("TEST");
        when(claimed.status()).thenReturn(LlmAnalysisJobStatus.RUNNING);
        when(claimed.phase()).thenReturn(LlmAnalysisPhase.PREPARE);
        when(claimed.leaseOwner()).thenReturn("provider-worker");
        LlmAnalysisJob terminal = mock(LlmAnalysisJob.class);
        LlmAnalysisWorkerProperties properties = properties();
        LlmProviderRequest request = new LlmProviderRequest(
                jobId,
                "TEST",
                "test-model",
                "system",
                "{}",
                "{}",
                new BigDecimal("0.2"),
                1_000,
                NOW.plusSeconds(90)
        );
        PreparedLlmProviderRequest prepared = new PreparedLlmProviderRequest(
                request,
                "c".repeat(64)
        );
        LlmProviderRequestFactory requestFactory = mock(LlmProviderRequestFactory.class);
        when(requestFactory.prepare(
                claimed,
                NOW,
                properties.providerCallTimeout()
        )).thenReturn(prepared);
        LlmProviderPreflight preflight = new LlmProviderPreflight(
                100,
                8_000,
                new BigDecimal("1.00"),
                "RUB"
        );
        LlmProviderBudgetGuard budgetGuard = mock(LlmProviderBudgetGuard.class);
        LlmProviderPreflightException rejection =
                new LlmProviderPreflightException(
                        LlmProviderPreflightFailureKind.COST_BUDGET_EXCEEDED,
                        "LLM request exceeds configured cost budget"
                );
        org.mockito.Mockito.doThrow(rejection)
                .when(budgetGuard)
                .validate(request, preflight);
        LlmProviderCallPersistence persistence = mock(
                LlmProviderCallPersistence.class
        );
        when(persistence.recordPreflightRejection(
                jobId,
                "provider-worker",
                "LLM_PREFLIGHT_COST_BUDGET_EXCEEDED",
                rejection.getMessage(),
                NOW
        )).thenReturn(terminal);
        LlmProviderOrchestrationMetrics metrics = mock(
                LlmProviderOrchestrationMetrics.class
        );
        LlmProviderCallExecutionService service =
                new LlmProviderCallExecutionService(
                        new LlmProviderRegistry(List.of(
                                new PreflightOnlyProvider(preflight)
                        )),
                        requestFactory,
                        budgetGuard,
                        persistence,
                        properties,
                        metrics,
                        Clock.fixed(NOW, ZoneOffset.UTC)
                );

        LlmAnalysisJob result = service.execute(claimed, "provider-worker");

        assertThat(result).isSameAs(terminal);
        verify(metrics).preflightRejected("COST_BUDGET_EXCEEDED");
        verify(persistence).recordPreflightRejection(
                jobId,
                "provider-worker",
                "LLM_PREFLIGHT_COST_BUDGET_EXCEEDED",
                rejection.getMessage(),
                NOW
        );
        verify(persistence, never()).start(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    private LlmAnalysisWorkerProperties properties() {
        return new LlmAnalysisWorkerProperties(
                false,
                Duration.ofSeconds(5),
                Duration.ofMinutes(2),
                Duration.ofSeconds(15),
                Duration.ofSeconds(30),
                Duration.ofSeconds(90),
                524_288,
                new BigDecimal("50.00")
        );
    }

    private static final class PreflightOnlyProvider implements LlmProviderClient {

        private final LlmProviderPreflight preflight;

        private PreflightOnlyProvider(LlmProviderPreflight preflight) {
            this.preflight = preflight;
        }

        @Override
        public String providerCode() {
            return "TEST";
        }

        @Override
        public LlmProviderPreflight preflight(LlmProviderRequest request) {
            return preflight;
        }

        @Override
        public LlmProviderResponseReceipt generate(LlmProviderRequest request) {
            throw new AssertionError("generate must not be called after preflight rejection");
        }
    }

    private static final class FailingProvider implements LlmProviderClient {

        private final LlmProviderException failure;

        private FailingProvider(LlmProviderException failure) {
            this.failure = failure;
        }

        @Override
        public String providerCode() {
            return "TEST";
        }

        @Override
        public LlmProviderPreflight preflight(LlmProviderRequest request) {
            return new LlmProviderPreflight(
                    100,
                    8_000,
                    new BigDecimal("1.00"),
                    "RUB"
            );
        }

        @Override
        public LlmProviderResponseReceipt generate(LlmProviderRequest request) {
            throw failure;
        }
    }

    private static final class TestProviderException extends LlmProviderException {

        private TestProviderException() {
            super("Provider is temporarily unavailable", null);
        }

        @Override
        public String failureCode() {
            return "TRANSIENT_PROVIDER";
        }

        @Override
        public LlmProviderOutcome outcome() {
            return LlmProviderOutcome.RESPONSE_RECEIVED;
        }

        @Override
        public Integer httpStatus() {
            return 503;
        }

        @Override
        public Duration retryAfter() {
            return null;
        }

        @Override
        public boolean isRetryable() {
            return true;
        }
    }
}
