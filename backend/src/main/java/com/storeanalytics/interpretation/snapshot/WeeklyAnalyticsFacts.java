package com.storeanalytics.interpretation.snapshot;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.metrics.service.AverageKpiResult;
import com.storeanalytics.performance.service.StorePlanProgressView;
import com.storeanalytics.store.service.StoreDataStatusView;
import java.util.List;
import java.util.UUID;

public record WeeklyAnalyticsFacts(
        UUID storeId,
        WeeklyAnalyticsFactsQuery query,
        StoreDataStatusView sourceDataStatus,
        WeeklyPeriodFacts current,
        WeeklyPeriodFacts previous,
        AverageKpiResult averageComparisons,
        List<StorePlanProgressView> planContexts
) {

    public WeeklyAnalyticsFacts {
        requireNonNull(storeId, "storeId");
        requireNonNull(query, "query");
        requireNonNull(sourceDataStatus, "sourceDataStatus");
        requireNonNull(current, "current");
        requireNonNull(previous, "previous");
        requireNonNull(averageComparisons, "averageComparisons");
        planContexts = List.copyOf(requireNonNull(planContexts, "planContexts"));
        if (!storeId.equals(query.storeId())) {
            throw new IllegalArgumentException("storeId must match query.storeId");
        }
    }
}
