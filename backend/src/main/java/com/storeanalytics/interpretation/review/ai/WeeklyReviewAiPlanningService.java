package com.storeanalytics.interpretation.review.ai;

import com.storeanalytics.integration.llm.yandex.YandexLlmProperties;
import java.time.Clock;
import org.springframework.stereotype.Service;

@Service
public class WeeklyReviewAiPlanningService {

    private final WeeklyReviewAiJobStore jobStore;
    private final WeeklyReviewAiGenerationProperties properties;
    private final YandexLlmProperties yandexProperties;
    private final Clock clock;

    public WeeklyReviewAiPlanningService(
            WeeklyReviewAiJobStore jobStore,
            WeeklyReviewAiGenerationProperties properties,
            YandexLlmProperties yandexProperties,
            Clock clock
    ) {
        this.jobStore = jobStore;
        this.properties = properties;
        this.yandexProperties = yandexProperties;
        this.clock = clock;
    }

    public int plan() {
        return jobStore.enqueueLatest(
                properties.providerCode(),
                yandexProperties.getModelUri(),
                properties.maxProviderCalls(),
                properties.batchSize(),
                clock.instant(),
                properties.jobDeadline()
        );
    }
}
