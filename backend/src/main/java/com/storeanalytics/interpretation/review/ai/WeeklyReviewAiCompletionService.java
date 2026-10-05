package com.storeanalytics.interpretation.review.ai;

import com.storeanalytics.interpretation.generation.LlmProviderResponseReceipt;
import com.storeanalytics.interpretation.review.SellerWeeklyAiSourceFence;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;

/** Atomically publishes validated wording and completes its durable job. */
@Service
public class WeeklyReviewAiCompletionService {

    private final WeeklyReviewAiEnrichmentStore enrichmentStore;
    private final WeeklyReviewAiJobStore jobStore;
    private final SellerWeeklyAiSourceFence sourceFence;
    private final Clock clock;

    public WeeklyReviewAiCompletionService(
            WeeklyReviewAiEnrichmentStore enrichmentStore,
            WeeklyReviewAiJobStore jobStore,
            SellerWeeklyAiSourceFence sourceFence,
            Clock clock
    ) {
        this.enrichmentStore = enrichmentStore;
        this.jobStore = jobStore;
        this.sourceFence = sourceFence;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public void complete(
            WeeklyReviewAiJob job,
            WeeklyReviewAiAttempt attempt,
            String owner,
            PreparedWeeklyReviewAiRequest prepared,
            LlmProviderResponseReceipt response,
            WeeklyReviewAiValidationResult validation,
            Instant now
    ) {
        jobStore.preserveResponseReceipt(job, attempt, prepared, response, validation, now);
        boolean seller = SellerWeeklyReviewAiContract.isActive(job.promptVersion(), job.contentSchemaVersion());
        if (seller && !sourceFence.lockAndIsCurrent(job.snapshotId())) {
            jobStore.recordStaleResponse(job, attempt, owner, response, clock.instant());
            return;
        }
        Instant publishedAt = seller ? clock.instant() : now;
        // Lease/job completion is checked before publication; both changes roll back together.
        jobStore.recordSuccessfulAttempt(job, attempt, owner, response, validation, publishedAt);
        enrichmentStore.persist(
                job.snapshotId(),
                prepared.input(),
                validation,
                publishedAt,
                publishedAt
        );
    }
}
