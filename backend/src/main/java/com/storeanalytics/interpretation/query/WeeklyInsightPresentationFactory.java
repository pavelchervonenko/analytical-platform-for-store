package com.storeanalytics.interpretation.query;

import com.storeanalytics.interpretation.snapshot.PersistedWeeklySnapshot;
import org.springframework.stereotype.Component;

@Component
public class WeeklyInsightPresentationFactory {

    private final WeeklyInsightContentProjector contentProjector;
    private final WeeklyInsightFallbackFactory fallbackFactory;

    public WeeklyInsightPresentationFactory(
            WeeklyInsightContentProjector contentProjector,
            WeeklyInsightFallbackFactory fallbackFactory
    ) {
        this.contentProjector = contentProjector;
        this.fallbackFactory = fallbackFactory;
    }

    public WeeklyInsightContentView content(
            WeeklyInterpretationDetailView interpretation,
            PersistedWeeklySnapshot snapshot
    ) {
        return contentProjector.project(interpretation, snapshot);
    }

    public WeeklyInsightFallbackView fallback(PersistedWeeklySnapshot snapshot) {
        return fallbackFactory.create(snapshot);
    }
}
