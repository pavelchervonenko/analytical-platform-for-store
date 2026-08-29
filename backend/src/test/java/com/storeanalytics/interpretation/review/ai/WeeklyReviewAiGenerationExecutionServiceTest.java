package com.storeanalytics.interpretation.review.ai;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeanalytics.interpretation.generation.LlmProviderClient;
import com.storeanalytics.interpretation.generation.LlmProviderPreflight;
import com.storeanalytics.interpretation.generation.LlmProviderRegistry;
import com.storeanalytics.interpretation.generation.LlmProviderRequest;
import com.storeanalytics.interpretation.generation.LlmProviderResponseReceipt;
import com.storeanalytics.interpretation.review.PersistedWeeklyReviewSnapshot;
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
    void publishesOnlySemanticallyValidatedResponse() {
        LlmProviderResponseReceipt response = receipt(validResponse());
        WeeklyReviewAiValidationResult valid = semanticValid();
        when(provider.generate(prepared.request())).thenReturn(response);
        when(validator.validate(prepared.input(), response.responseBody()))
                .thenReturn(valid);

        service.execute(job, OWNER);

        verify(completionService).complete(
                job, attempt, OWNER, prepared, response, valid, NOW
        );
        verify(jobStore, never()).recordValidationFailure(
                any(), any(), any(), any(), any(), any(), any()
        );
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
        WeeklyReviewAiInput input = new WeeklyReviewAiInput(
                1,
                WeeklyReviewAiContract.PROMPT_VERSION,
                4,
                new WeeklyReviewAiInput.SummarySource(
                        "Чистая выручка выросла.",
                        List.of("STORE.NET_REVENUE"),
                        List.of()
                ),
                List.of(),
                List.of(),
                List.of(new WeeklyReviewAiInput.EvidenceSource(
                        "STORE.NET_REVENUE", "Чистая выручка", "RUB",
                        "1000", "900"
                ))
        );
        LlmProviderRequest request = new LlmProviderRequest(
                value.id(), value.providerCode(), value.requestedModel(),
                "system", "{\"contractVersion\":1}", "{}",
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
