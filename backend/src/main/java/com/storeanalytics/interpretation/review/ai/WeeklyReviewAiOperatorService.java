package com.storeanalytics.interpretation.review.ai;

import com.storeanalytics.integration.llm.yandex.YandexLlmProperties;
import com.storeanalytics.interpretation.review.PersistedWeeklyReviewSnapshot;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.ReportState;
import com.storeanalytics.interpretation.review.WeeklyReviewSnapshotStore;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class WeeklyReviewAiOperatorService {

    private final WeeklyReviewAiGenerationProperties properties;
    private final WeeklyReviewSnapshotStore snapshotStore;
    private final WeeklyReviewAiJobStore jobStore;
    private final YandexLlmProperties yandexProperties;
    private final Clock clock;

    public WeeklyReviewAiOperatorService(
            WeeklyReviewAiGenerationProperties properties,
            WeeklyReviewSnapshotStore snapshotStore,
            WeeklyReviewAiJobStore jobStore,
            YandexLlmProperties yandexProperties,
            Clock clock
    ) {
        this.properties = properties;
        this.snapshotStore = snapshotStore;
        this.jobStore = jobStore;
        this.yandexProperties = yandexProperties;
        this.clock = clock;
    }

    public WeeklyReviewAiJobView findJob(UUID jobId) {
        return jobStore.findById(jobId)
                .map(WeeklyReviewAiJobView::from)
                .orElseThrow(WeeklyReviewAiJobNotFoundException::new);
    }

    public WeeklyReviewAiJobView generate(UUID snapshotId) {
        if (!properties.enabled()) {
            throw new WeeklyReviewAiDisabledException();
        }
        PersistedWeeklyReviewSnapshot snapshot = snapshotStore
                .findById(snapshotId)
                .orElseThrow(WeeklyReviewAiSnapshotNotFoundException::new);
        ReportState state = snapshot.response().reportState();
        if (state != ReportState.READY && state != ReportState.PARTIAL) {
            throw new WeeklyReviewAiSnapshotNotEligibleException();
        }
        WeeklyReviewAiJob job = jobStore.enqueue(
                snapshot.id(),
                properties.providerCode(),
                yandexProperties.getModelUri(),
                properties.maxProviderCalls(),
                clock.instant(),
                properties.jobDeadline()
        );
        return WeeklyReviewAiJobView.from(job);
    }
}
