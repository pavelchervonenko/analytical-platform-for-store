package com.storeanalytics.interpretation.generation;

import com.storeanalytics.interpretation.config.LlmAnalysisPlannerProperties;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class LlmAnalysisPlanningService {

    private final LlmAnalysisPlanningStore planningStore;
    private final LlmAnalysisJobStore jobStore;
    private final LlmAnalysisRequestFactory requestFactory;
    private final LlmAnalysisPlannerProperties properties;
    private final Clock clock;

    public LlmAnalysisPlanningService(
            LlmAnalysisPlanningStore planningStore,
            LlmAnalysisJobStore jobStore,
            LlmAnalysisRequestFactory requestFactory,
            LlmAnalysisPlannerProperties properties,
            Clock clock
    ) {
        this.planningStore = planningStore;
        this.jobStore = jobStore;
        this.requestFactory = requestFactory;
        this.properties = properties;
        this.clock = clock;
    }

    public LlmAnalysisPlanningResult plan() {
        Instant now = clock.instant();
        int candidates = 0;
        int created = 0;
        for (LlmAnalysisPlanningStore.SnapshotTarget target
                : planningStore.eligibleSnapshots(properties.batchSize())) {
            candidates++;
            LlmAnalysisEnqueueResult result = jobStore.enqueue(
                    requestFactory.automatic(target, now),
                    now
            );
            if (result.created()) {
                created++;
            }
        }
        return new LlmAnalysisPlanningResult(
                candidates,
                created,
                candidates - created
        );
    }
}
