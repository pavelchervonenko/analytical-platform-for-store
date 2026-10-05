package com.storeanalytics.interpretation.review.ai;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeanalytics.interpretation.generation.LlmProviderClient;
import com.storeanalytics.interpretation.generation.LlmProviderPreflight;
import com.storeanalytics.interpretation.generation.LlmProviderRegistry;
import com.storeanalytics.interpretation.generation.LlmProviderRequest;
import com.storeanalytics.interpretation.generation.LlmProviderResponseReceipt;
import com.storeanalytics.interpretation.review.PersistedWeeklyReviewSnapshot;
import com.storeanalytics.interpretation.review.PersistedWeeklyReviewV3Snapshot;
import com.storeanalytics.interpretation.review.WeeklyReviewSnapshotStore;
import com.storeanalytics.interpretation.validation.LlmValidationOutcome;
import com.storeanalytics.interpretation.validation.LlmValidationViolation;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WeeklyReviewAiGenerationExecutionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");
    private static final String OWNER = "worker";

    private final WeeklyReviewAiJobStore jobStore = mock(
            WeeklyReviewAiJobStore.class
    );
    private final WeeklyReviewSnapshotStore snapshotStore = mock(
            WeeklyReviewSnapshotStore.class
    );
    private final WeeklyReviewAiProviderRequestFactory requestFactory = mock(
            WeeklyReviewAiProviderRequestFactory.class
    );
    private final WeeklyReviewAiSemanticValidator validator = mock(
            WeeklyReviewAiSemanticValidator.class
    );
    private final WeeklyReviewAiBudgetGuard budgetGuard = mock(
            WeeklyReviewAiBudgetGuard.class
    );
    private final WeeklyReviewAiCompletionService completionService = mock(
            WeeklyReviewAiCompletionService.class
    );
    private final LlmProviderRegistry registry = mock(LlmProviderRegistry.class);
    private final LlmProviderClient provider = mock(LlmProviderClient.class);
    private final WeeklyReviewAiGenerationProperties properties =
            WeeklyReviewAiTestProperties.properties(true, false, true);
    private final WeeklyReviewAiGenerationExecutionService service =
            new WeeklyReviewAiGenerationExecutionService(
                    jobStore,
                    snapshotStore,
                    new WeeklyReviewAiGenerationSupport(
                            requestFactory,
                            validator,
                            budgetGuard,
                            completionService,
                            registry,
                            properties
                    ),
                    Clock.fixed(NOW, ZoneOffset.UTC)
            );

    private WeeklyReviewAiJob job;
    private PersistedWeeklyReviewSnapshot snapshot;
    private PreparedWeeklyReviewAiRequest prepared;
    private LlmProviderPreflight preflight;
    private WeeklyReviewAiAttempt attempt;

    @BeforeEach
    void setUp() {
        job = job();
        snapshot = mock(PersistedWeeklyReviewSnapshot.class);
        prepared = prepared(job);
        preflight = new LlmProviderPreflight(
                1000, 8000, new BigDecimal("3.00"), "RUB"
        );
        attempt = new WeeklyReviewAiAttempt(
                UUID.randomUUID(), job.id(), 1, NOW
        );
        when(snapshotStore.findById(job.snapshotId()))
                .thenReturn(Optional.of(snapshot));
        when(requestFactory.prepare(any())).thenReturn(prepared);
        when(registry.requireProvider("YANDEX")).thenReturn(provider);
        when(provider.preflight(prepared.request())).thenReturn(preflight);
        when(jobStore.actualCostSince(any())).thenReturn(BigDecimal.ZERO);
        when(jobStore.startAttempt(job, OWNER, prepared, preflight, NOW))
                .thenReturn(attempt);
    }

    @Test
    void rejectsLegacyJobBeforeSnapshotOrProviderPreparation() {
        WeeklyReviewAiJob legacy = new WeeklyReviewAiJob(
                job.id(),
                job.snapshotId(),
                WeeklyReviewAiContract.LEGACY_PROMPT_VERSION,
                job.contentSchemaVersion(),
                job.providerCode(),
                job.requestedModel(),
                job.status(),
                job.attemptCount(),
                job.maxAttempts(),
                job.nextAttemptAt(),
                job.deadlineAt(),
                job.leaseOwner(),
                job.leaseUntil(),
                job.lastErrorCode(),
                job.lastErrorMessage(),
                job.lastValidationCodes(),
                job.createdAt(),
                job.updatedAt()
        );

        service.execute(legacy, OWNER);

        verify(jobStore).failClaimed(
                legacy, OWNER, "JOB_CONTRACT_MISMATCH",
                "Weekly review AI job contract is not active", NOW
        );
        verify(snapshotStore, never()).findById(any());
        verify(requestFactory, never()).prepare(any());
        verify(provider, never()).generate(any());
    }

    @Test
    void publishesOnlySemanticallyValidatedResponse() {
        LlmProviderResponseReceipt response = receipt(validResponse());
        WeeklyReviewAiValidationResult valid = semanticValid();
        when(provider.generate(prepared.request())).thenReturn(response);
        when(validator.validate(prepared.input(), response.responseBody()))
                .thenReturn(valid);

        service.execute(job, OWNER);

        org.mockito.InOrder order = inOrder(jobStore, completionService);
        order.verify(jobStore).preserveResponseReceipt(job, attempt, prepared, response, valid, NOW);
        order.verify(completionService).complete(job, attempt, OWNER, prepared, response, valid, NOW);
        verify(completionService).complete(
                job, attempt, OWNER, prepared, response, valid, NOW
        );
        verify(jobStore, never()).recordValidationFailure(
                any(), any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    void lostLeaseAfterResponsePreservesReceiptWithoutAnotherProviderCall() {
        LlmProviderResponseReceipt response = receipt(validResponse());
        WeeklyReviewAiValidationResult valid = semanticValid();
        when(provider.generate(prepared.request())).thenReturn(response);
        when(validator.validate(prepared.input(), response.responseBody())).thenReturn(valid);
        doThrow(new WeeklyReviewAiLeaseLostException()).when(completionService)
                .complete(job, attempt, OWNER, prepared, response, valid, NOW);
        service.execute(job, OWNER);
        verify(jobStore).preserveResponseReceipt(job, attempt, prepared, response, valid, NOW);
        verify(provider).generate(prepared.request());
        verify(jobStore, never()).failClaimed(any(), any(), any(), any(), any());
        verify(jobStore, never()).recordValidationFailure(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void validatorCrashStillPreservesPaidResponseWithSafeFailureCode() {
        LlmProviderResponseReceipt response = receipt(validResponse());
        when(provider.generate(prepared.request())).thenReturn(response);
        when(validator.validate(prepared.input(), response.responseBody()))
                .thenThrow(new IllegalStateException("synthetic validator failure"));
        service.execute(job, OWNER);
        WeeklyReviewAiValidationResult invalid = WeeklyReviewAiValidationResult.invalid(
                LlmValidationOutcome.SEMANTIC_INVALID,
                List.of(new LlmValidationViolation("VALIDATION_EXECUTION_FAILED", "$", null)));
        verify(jobStore).preserveResponseReceipt(job, attempt, prepared, response, invalid, NOW);
        verify(jobStore).recordValidationFailure(job, attempt, OWNER, response, invalid,
                properties.retryDelay(1), NOW);
        verify(completionService, never()).complete(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void lostLeaseOnRejectedResponsePreservesReceiptWithoutSchedulingAnotherPaidAttempt() {
        LlmProviderResponseReceipt response = receipt("{}");
        WeeklyReviewAiValidationResult invalid = WeeklyReviewAiValidationResult.invalid(
                LlmValidationOutcome.SEMANTIC_INVALID,
                List.of(new LlmValidationViolation("UNAPPROVED_NUMBER", "$", null)));
        when(provider.generate(prepared.request())).thenReturn(response);
        when(validator.validate(prepared.input(), response.responseBody())).thenReturn(invalid);
        doThrow(new WeeklyReviewAiLeaseLostException()).when(jobStore)
                .recordValidationFailure(job, attempt, OWNER, response, invalid, properties.retryDelay(1), NOW);
        service.execute(job, OWNER);
        verify(jobStore).preserveResponseReceipt(job, attempt, prepared, response, invalid, NOW);
        verify(provider).generate(prepared.request());
        verify(completionService, never()).complete(any(), any(), any(), any(), any(), any(), any());
        verify(jobStore, never()).failClaimed(any(), any(), any(), any(), any());
    }

    @Test
    void rejectsInvalidResponseWithoutPublicationAndSchedulesRetry() {
        LlmProviderResponseReceipt response = receipt("{}");
        WeeklyReviewAiValidationResult invalid =
                WeeklyReviewAiValidationResult.invalid(
                        LlmValidationOutcome.SEMANTIC_INVALID,
                        List.of(new LlmValidationViolation(
                                "UNAPPROVED_NUMBER", "$.summary.text", "12"
                        ))
                );
        when(provider.generate(prepared.request())).thenReturn(response);
        when(validator.validate(prepared.input(), response.responseBody()))
                .thenReturn(invalid);

        service.execute(job, OWNER);

        verify(jobStore).recordValidationFailure(
                job,
                attempt,
                OWNER,
                response,
                invalid,
                properties.retryDelay(1),
                NOW
        );
        verify(completionService, never()).complete(
                any(), any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    void failsClosedBeforeProviderCallWhenBudgetGateRejects() {
        org.mockito.Mockito.doThrow(new WeeklyReviewAiBudgetException(
                "DAILY_BUDGET_EXCEEDED", "Daily budget exceeded"
        )).when(budgetGuard).validate(
                eq(prepared.request()), eq(preflight), eq(BigDecimal.ZERO)
        );

        service.execute(job, OWNER);

        verify(jobStore).failClaimed(
                job,
                OWNER,
                "PREFLIGHT_DAILY_BUDGET_EXCEEDED",
                "Daily budget exceeded",
                NOW
        );
        verify(provider, never()).generate(any());
        verify(jobStore, never()).startAttempt(any(), any(), any(), any(), any());
    }


    @Test
    void failsClosedWhenAtomicBudgetReservationRejectsAConcurrentCall() {
        org.mockito.Mockito.doThrow(new WeeklyReviewAiBudgetException(
                "DAILY_BUDGET_EXCEEDED", "Daily budget exceeded"
        )).when(jobStore).startAttempt(
                job, OWNER, prepared, preflight, NOW
        );

        service.execute(job, OWNER);

        verify(jobStore).failClaimed(
                job,
                OWNER,
                "PREFLIGHT_DAILY_BUDGET_EXCEEDED",
                "Daily budget exceeded",
                NOW
        );
        verify(provider, never()).generate(any());
    }

    @Test
    void lostLeaseStopsBeforeProviderWithoutTouchingTheNewOwnersJob() {
        org.mockito.Mockito.doThrow(new WeeklyReviewAiLeaseLostException()).when(jobStore)
                .startAttempt(job, OWNER, prepared, preflight, NOW);

        service.execute(job, OWNER);

        verify(provider, never()).generate(any());
        verify(jobStore, never()).failClaimed(any(), any(), any(), any(), any());
        verify(completionService, never()).complete(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void sellerJobStopsBeforeProviderWhenSnapshotIsNotCurrent() {
        WeeklyReviewAiJob sellerJob = sellerJob();
        PersistedWeeklyReviewV3Snapshot sellerSnapshot = mock(PersistedWeeklyReviewV3Snapshot.class);
        SellerWeeklyReviewAiFreshnessGuard guard = mock(SellerWeeklyReviewAiFreshnessGuard.class);
        when(snapshotStore.findV3ById(sellerJob.snapshotId())).thenReturn(Optional.of(sellerSnapshot));
        when(guard.isCurrent(sellerSnapshot)).thenReturn(false);

        sellerService(guard).execute(sellerJob, OWNER);

        verify(jobStore).failClaimed(sellerJob, OWNER, "SNAPSHOT_NOT_CURRENT",
                "Seller source changed before provider execution", NOW);
        verify(requestFactory, never()).prepare(any());
        verify(provider, never()).generate(any());
    }

    @Test
    void atomicSourceConflictAfterCheapCheckStopsBeforeProvider() {
        WeeklyReviewAiJob sellerJob = sellerJob();
        PersistedWeeklyReviewV3Snapshot sellerSnapshot = mock(PersistedWeeklyReviewV3Snapshot.class);
        SellerWeeklyReviewAiFreshnessGuard guard = mock(SellerWeeklyReviewAiFreshnessGuard.class);
        PreparedWeeklyReviewAiRequest request = prepared(sellerJob);
        when(snapshotStore.findV3ById(sellerJob.snapshotId())).thenReturn(Optional.of(sellerSnapshot));
        when(guard.isCurrent(sellerSnapshot)).thenReturn(true);
        when(requestFactory.prepare(any())).thenReturn(request);
        when(provider.preflight(request.request())).thenReturn(preflight);
        when(jobStore.startAttempt(sellerJob, OWNER, request, preflight, NOW))
                .thenThrow(new WeeklyReviewAiSnapshotNotCurrentException());

        sellerService(guard).execute(sellerJob, OWNER);

        verify(jobStore).failClaimed(sellerJob, OWNER, "SNAPSHOT_NOT_CURRENT",
                "Seller source changed before provider execution", NOW);
        verify(provider, never()).generate(any());
    }

    @Test
    void sellerJobPreservesBilledReceiptButDoesNotPublishAfterSourceChanges() {
        WeeklyReviewAiJob sellerJob = sellerJob();
        PersistedWeeklyReviewV3Snapshot sellerSnapshot = mock(PersistedWeeklyReviewV3Snapshot.class);
        SellerWeeklyReviewAiFreshnessGuard guard = mock(SellerWeeklyReviewAiFreshnessGuard.class);
        PreparedWeeklyReviewAiRequest sellerPrepared = prepared(sellerJob);
        WeeklyReviewAiAttempt sellerAttempt = new WeeklyReviewAiAttempt(
                UUID.randomUUID(), sellerJob.id(), 1, NOW);
        LlmProviderResponseReceipt response = receipt(validResponse());
        when(snapshotStore.findV3ById(sellerJob.snapshotId())).thenReturn(Optional.of(sellerSnapshot));
        when(guard.isCurrent(sellerSnapshot)).thenReturn(true, true, false);
        when(requestFactory.prepare(any())).thenReturn(sellerPrepared);
        when(provider.preflight(sellerPrepared.request())).thenReturn(preflight);
        when(jobStore.startAttempt(sellerJob, OWNER, sellerPrepared, preflight, NOW)).thenReturn(sellerAttempt);
        when(provider.generate(sellerPrepared.request())).thenReturn(response);
        when(validator.validate(sellerPrepared.input(), response.responseBody())).thenReturn(semanticValid());

        sellerService(guard).execute(sellerJob, OWNER);

        verify(provider).generate(sellerPrepared.request());
        verify(jobStore).recordStaleResponse(sellerJob, sellerAttempt, OWNER, response, NOW);
        verify(completionService, never()).complete(any(), any(), any(), any(), any(), any(), any());
        verify(jobStore, never()).recordValidationFailure(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void sourceRevisionChurnMayRefreshTheSameSnapshotBeforeTheOnlyProviderCall() {
        WeeklyReviewAiJob sellerJob = sellerJob();
        PersistedWeeklyReviewV3Snapshot snapshot = mock(PersistedWeeklyReviewV3Snapshot.class);
        SellerWeeklyReviewAiFreshnessGuard guard = mock(SellerWeeklyReviewAiFreshnessGuard.class);
        PreparedWeeklyReviewAiRequest request = prepared(sellerJob);
        WeeklyReviewAiAttempt attempt = new WeeklyReviewAiAttempt(UUID.randomUUID(), sellerJob.id(), 1, NOW);
        LlmProviderResponseReceipt response = receipt(validResponse());
        WeeklyReviewAiValidationResult valid = semanticValid();
        when(snapshotStore.findV3ById(sellerJob.snapshotId())).thenReturn(Optional.of(snapshot));
        when(guard.isCurrent(snapshot)).thenReturn(false, true, true);
        when(guard.refreshIfSameSnapshot(snapshot, NOW)).thenReturn(true);
        when(requestFactory.prepare(any())).thenReturn(request);
        when(provider.preflight(request.request())).thenReturn(preflight);
        when(jobStore.startAttempt(sellerJob, OWNER, request, preflight, NOW)).thenReturn(attempt);
        when(provider.generate(request.request())).thenReturn(response);
        when(validator.validate(request.input(), response.responseBody())).thenReturn(valid);

        sellerService(guard).execute(sellerJob, OWNER);

        verify(guard).refreshIfSameSnapshot(snapshot, NOW);
        verify(provider).generate(request.request());
        verify(completionService).complete(sellerJob, attempt, OWNER, request, response, valid, NOW);
    }

    @Test
    void sellerJobFailsClosedWhenFreshnessReadFails() {
        WeeklyReviewAiJob sellerJob = sellerJob();
        PersistedWeeklyReviewV3Snapshot sellerSnapshot = mock(PersistedWeeklyReviewV3Snapshot.class);
        SellerWeeklyReviewAiFreshnessGuard guard = mock(SellerWeeklyReviewAiFreshnessGuard.class);
        when(snapshotStore.findV3ById(sellerJob.snapshotId())).thenReturn(Optional.of(sellerSnapshot));
        when(guard.isCurrent(sellerSnapshot)).thenThrow(new IllegalStateException("synthetic read failure"));

        sellerService(guard).execute(sellerJob, OWNER);

        verify(jobStore).failClaimed(sellerJob, OWNER, "SNAPSHOT_NOT_CURRENT",
                "Seller source changed before provider execution", NOW);
        verify(provider, never()).generate(any());
    }

    private WeeklyReviewAiGenerationExecutionService sellerService(SellerWeeklyReviewAiFreshnessGuard guard) {
        return new WeeklyReviewAiGenerationExecutionService(jobStore, snapshotStore,
                new WeeklyReviewAiGenerationSupport(requestFactory, validator, budgetGuard,
                        completionService, registry, properties), Clock.fixed(NOW, ZoneOffset.UTC), guard);
    }

    private WeeklyReviewAiJob sellerJob() {
        WeeklyReviewAiJob legacy = job();
        return new WeeklyReviewAiJob(legacy.id(), legacy.snapshotId(),
                SellerWeeklyReviewAiContract.PROMPT_VERSION, legacy.contentSchemaVersion(),
                legacy.providerCode(), legacy.requestedModel(), legacy.status(), legacy.attemptCount(),
                legacy.maxAttempts(), legacy.nextAttemptAt(), legacy.deadlineAt(), legacy.leaseOwner(),
                legacy.leaseUntil(), legacy.lastErrorCode(), legacy.lastErrorMessage(),
                legacy.lastValidationCodes(), legacy.createdAt(), legacy.updatedAt());
    }

    private WeeklyReviewAiJob job() {
        return new WeeklyReviewAiJob(
                UUID.randomUUID(),
                UUID.randomUUID(),
                WeeklyReviewAiContract.PROMPT_VERSION,
                4,
                "YANDEX",
                "gpt://folder/yandexgpt-5.1",
                WeeklyReviewAiJobStatus.RUNNING,
                0,
                2,
                NOW,
                NOW.plusSeconds(3600),
                OWNER,
                NOW.plusSeconds(240),
                null,
                null,
                List.of(),
                NOW.minusSeconds(60),
                NOW
        );
    }

    private PreparedWeeklyReviewAiRequest prepared(WeeklyReviewAiJob value) {
        WeeklyReviewAiInput input =
                WeeklyReviewAiTestFixtures.minimalInput("POSITIVE");
        LlmProviderRequest request = new LlmProviderRequest(
                value.id(), value.providerCode(), value.requestedModel(),
                "system", "{\"contractVersion\":2}", "{}",
                new BigDecimal("0.1"), 1400, NOW.plusSeconds(180)
        );
        return new PreparedWeeklyReviewAiRequest(
                request, "a".repeat(64), input, "b".repeat(64)
        );
    }

    private LlmProviderResponseReceipt receipt(String responseBody) {
        return new LlmProviderResponseReceipt(
                responseBody,
                "gpt://folder/yandexgpt-5.1",
                "provider-request",
                1000, 100, 0, 0, 1100,
                new BigDecimal("2.00"), "RUB", 500L, 200
        );
    }

    private WeeklyReviewAiValidationResult semanticValid() {
        WeeklyReviewAiContent content = new WeeklyReviewAiContent(
                4,
                new WeeklyReviewAiContent.Summary(
                        "Чистая выручка выросла.",
                        List.of("STORE.NET_REVENUE")
                ),
                List.of(),
                List.of()
        );
        return WeeklyReviewAiValidationResult.semanticallyValid(
                content, validResponse()
        );
    }

    private String validResponse() {
        return """
                {
                  "schemaVersion": 4,
                  "summary": {
                    "text": "Чистая выручка выросла.",
                    "evidenceRefs": ["STORE.NET_REVENUE"]
                  },
                  "factorExplanations": [],
                  "actionWordings": []
                }
                """;
    }
}
