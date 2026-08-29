package com.storeanalytics.interpretation.review.ai;

import com.storeanalytics.interpretation.generation.LlmProviderClient;
import com.storeanalytics.interpretation.generation.LlmProviderException;
import com.storeanalytics.interpretation.generation.LlmProviderPreflight;
import com.storeanalytics.interpretation.generation.LlmProviderResponseReceipt;
import com.storeanalytics.interpretation.review.PersistedWeeklyReviewSnapshot;
import com.storeanalytics.interpretation.review.WeeklyReviewSnapshotStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;

@Service
public class WeeklyReviewAiGenerationExecutionService {

    private final WeeklyReviewAiJobStore jobStore;
    private final WeeklyReviewSnapshotStore snapshotStore;
    private final WeeklyReviewAiGenerationSupport support;
    private final Clock clock;

    public WeeklyReviewAiGenerationExecutionService(
            WeeklyReviewAiJobStore jobStore,
            WeeklyReviewSnapshotStore snapshotStore,
            WeeklyReviewAiGenerationSupport support,
            Clock clock
    ) {
        this.jobStore = jobStore;
        this.snapshotStore = snapshotStore;
        this.support = support;
        this.clock = clock;
    }

    public void execute(WeeklyReviewAiJob job, String owner) {
        Instant now = clock.instant();
        if (!WeeklyReviewAiContract.isActive(
                job.promptVersion(), job.contentSchemaVersion())) {
            jobStore.failClaimed(
                    job, owner, "JOB_CONTRACT_MISMATCH",
                    "Weekly review AI job contract is not active", now
            );
            return;
        }
        PersistedWeeklyReviewSnapshot snapshot = snapshotStore
                .findById(job.snapshotId())
                .orElse(null);
        if (snapshot == null) {
            jobStore.failClaimed(
                    job, owner, "SNAPSHOT_NOT_FOUND",
                    "Exact weekly review snapshot no longer exists", now
            );
            return;
        }
        PreparedWeeklyReviewAiRequest prepared;
        LlmProviderClient provider;
        LlmProviderPreflight preflight;
        try {
            prepared = support.requestFactory().prepare(
                    new WeeklyReviewAiProviderRequestCommand(
                            job.id(),
                            snapshot,
                            job.providerCode(),
                            job.requestedModel(),
                            support.properties().temperature(),
                            support.properties().maxOutputTokens(),
                            now,
                            support.properties().providerCallTimeout(),
                            job.deadlineAt(),
                            job.lastValidationCodes()
                    )
            );
            provider = support.providerRegistry().requireProvider(job.providerCode());
            preflight = provider.preflight(prepared.request());
            support.budgetGuard().validate(
                    prepared.request(),
                    preflight,
                    jobStore.actualCostSince(startOfUtcDay(now))
            );
        } catch (WeeklyReviewAiBudgetException failure) {
            jobStore.failClaimed(
                    job, owner, "PREFLIGHT_" + failure.code(),
                    failure.getMessage(), clock.instant()
            );
            return;
        } catch (LlmProviderException failure) {
            jobStore.failClaimed(
                    job, owner, "PREFLIGHT_PROVIDER_" + failure.failureCode(),
                    failure.getMessage(), clock.instant()
            );
            return;
        } catch (RuntimeException failure) {
            jobStore.failClaimed(
                    job, owner, "REQUEST_PREPARATION_FAILED",
                    "Weekly review AI request preparation failed", clock.instant()
            );
            return;
        }

        WeeklyReviewAiAttempt attempt;
        try {
            attempt = jobStore.startAttempt(
                    job, owner, prepared, preflight, clock.instant()
            );
        } catch (WeeklyReviewAiBudgetException failure) {
            jobStore.failClaimed(
                    job, owner, "PREFLIGHT_" + failure.code(),
                    failure.getMessage(), clock.instant()
            );
            return;
        }
        LlmProviderResponseReceipt response;
        try {
            response = provider.generate(prepared.request());
        } catch (LlmProviderException failure) {
            jobStore.recordProviderFailure(
                    job,
                    attempt,
                    owner,
                    failure,
                    support.properties().retryDelay(attempt.attemptNumber()),
                    clock.instant()
            );
            return;
        }
        WeeklyReviewAiValidationResult validation = support.validator().validate(
                prepared.input(), response.responseBody()
        );
        if (!validation.semanticValidated()) {
            jobStore.recordValidationFailure(
                    job,
                    attempt,
                    owner,
                    response,
                    validation,
                    support.properties().retryDelay(attempt.attemptNumber()),
                    clock.instant()
            );
            return;
        }
        support.completionService().complete(
                job,
                attempt,
                owner,
                prepared,
                response,
                validation,
                clock.instant()
        );
    }

    private Instant startOfUtcDay(Instant value) {
        return value.atZone(ZoneOffset.UTC).toLocalDate()
                .atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
