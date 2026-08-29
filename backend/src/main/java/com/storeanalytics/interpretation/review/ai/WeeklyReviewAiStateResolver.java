package com.storeanalytics.interpretation.review.ai;

import com.storeanalytics.interpretation.review.PersistedWeeklyReviewSnapshot;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.AiEnhancement;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.AiState;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.ReportState;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public final class WeeklyReviewAiStateResolver {

    private final WeeklyReviewAiGenerationProperties properties;
    private final WeeklyReviewAiJobStore jobStore;

    public WeeklyReviewAiStateResolver(
            WeeklyReviewAiGenerationProperties properties,
            WeeklyReviewAiJobStore jobStore
    ) {
        this.properties = properties;
        this.jobStore = jobStore;
    }

    public WeeklyReviewResponse apply(
            PersistedWeeklyReviewSnapshot snapshot,
            Instant now
    ) {
        WeeklyReviewResponse response = snapshot.response();
        if (response.reportState() == ReportState.BLOCKED) {
            return response.withAiEnhancement(new AiEnhancement(
                    AiState.NOT_APPLICABLE, null, null, null
            ));
        }
        if (!properties.enabled()) {
            return response.withAiEnhancement(new AiEnhancement(
                    AiState.DISABLED, null, null, null
            ));
        }
        AiState state = jobStore.findBySnapshot(snapshot.id())
                .map(job -> state(job, now))
                .orElse(properties.plannerEnabled()
                        ? AiState.PREPARING : AiState.UNAVAILABLE);
        return response.withAiEnhancement(new AiEnhancement(
                state,
                WeeklyReviewAiContract.PROMPT_VERSION,
                WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION,
                null
        ));
    }

    private AiState state(WeeklyReviewAiJob job, Instant now) {
        return switch (job.status()) {
            case FAILED, SUCCEEDED -> AiState.UNAVAILABLE;
            case PENDING, RUNNING, RETRY_WAIT ->
                    now.isAfter(job.createdAt().plus(properties.preparationSla()))
                            ? AiState.DELAYED : AiState.PREPARING;
        };
    }
}
