package com.storeanalytics.interpretation.review.ai;

import com.storeanalytics.interpretation.generation.LlmProviderResponseReceipt;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Atomically publishes validated wording and completes its durable job. */
@Service
public class WeeklyReviewAiCompletionService {

    private final WeeklyReviewAiEnrichmentStore enrichmentStore;
    private final WeeklyReviewAiJobStore jobStore;

    public WeeklyReviewAiCompletionService(
            WeeklyReviewAiEnrichmentStore enrichmentStore,
            WeeklyReviewAiJobStore jobStore
    ) {
        this.enrichmentStore = enrichmentStore;
        this.jobStore = jobStore;
    }

    @Transactional
    public void complete(
            WeeklyReviewAiJob job,
            WeeklyReviewAiAttempt attempt,
            String owner,
            PreparedWeeklyReviewAiRequest prepared,
            LlmProviderResponseReceipt response,
            WeeklyReviewAiValidationResult validation,
            Instant now
    ) {
        enrichmentStore.persist(
                job.snapshotId(),
                prepared.input(),
                validation,
                now,
                now
        );
        jobStore.recordSuccessfulAttempt(
                job, attempt, owner, response, validation, now
        );
    }
}
