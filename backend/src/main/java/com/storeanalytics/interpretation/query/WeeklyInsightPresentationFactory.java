package com.storeanalytics.interpretation.query;

import com.storeanalytics.interpretation.snapshot.PersistedWeeklySnapshot;
import org.springframework.stereotype.Component;

@Component
public class WeeklyInsightPresentationFactory {

    private final WeeklyInsightContentProjector contentProjector;
    private final WeeklyInsightFallbackFactory fallbackFactory;
    private final WeeklyInsightEvidenceProjector evidenceProjector;

    public WeeklyInsightPresentationFactory(
            WeeklyInsightContentProjector contentProjector,
            WeeklyInsightFallbackFactory fallbackFactory,
            WeeklyInsightEvidenceProjector evidenceProjector
    ) {
        this.contentProjector = contentProjector;
        this.fallbackFactory = fallbackFactory;
        this.evidenceProjector = evidenceProjector;
    }

    public WeeklyInsightContentView content(
            WeeklyInterpretationDetailView interpretation,
            PersistedWeeklySnapshot snapshot
    ) {
        WeeklyInsightContentView content = contentProjector.project(
                interpretation, snapshot
        );
        return evidenceProjector.project(content, snapshot);
    }

    public WeeklyInsightFallbackView fallback(PersistedWeeklySnapshot snapshot) {
        return fallbackFactory.create(snapshot);
    }
}
