package com.storeanalytics.interpretation.review.ai;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse.AiEnhancement;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.AiState;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.ReportState;
import com.storeanalytics.interpretation.review.WeeklyReviewV3Response;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Read-time status only; the deterministic persisted payload and its hash remain unchanged. */
@Component
public final class SellerWeeklyReviewAiStateResolver {
    private final WeeklyReviewAiGenerationProperties properties;
    private final WeeklyReviewAiJobStore jobs;

    public SellerWeeklyReviewAiStateResolver(WeeklyReviewAiGenerationProperties properties,
                                            WeeklyReviewAiJobStore jobs) {
        this.properties = properties;
        this.jobs = jobs;
    }

    public WeeklyReviewV3Response apply(WeeklyReviewV3Response report, Instant now) {
        if (report.reportState() == ReportState.BLOCKED) {
            return report.withAiEnhancement(new AiEnhancement(AiState.NOT_APPLICABLE, null, null, null));
        }
        if (!properties.enabled()) {
            return report.withAiEnhancement(new AiEnhancement(AiState.DISABLED, null, null, null));
        }
        UUID snapshotId = UUID.fromString(report.provenance().snapshotPublicId());
        AiState state = jobs.findBySnapshot(snapshotId)
                .map(job -> switch (job.status()) {
                    case FAILED, SUCCEEDED -> AiState.UNAVAILABLE;
                    case PENDING, RUNNING, RETRY_WAIT -> now.isAfter(job.createdAt().plus(properties.preparationSla()))
                            ? AiState.DELAYED : AiState.PREPARING;
                }).orElseGet(() -> jobs.hasSellerWeekPublicationBlocker(snapshotId, now)
                        ? AiState.UNAVAILABLE : properties.plannerEnabled() ? AiState.PREPARING : AiState.UNAVAILABLE);
        return report.withAiEnhancement(new AiEnhancement(state, SellerWeeklyReviewAiContract.PROMPT_VERSION, 4, null));
    }
}
