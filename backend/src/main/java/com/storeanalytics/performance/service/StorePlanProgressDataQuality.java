package com.storeanalytics.performance.service;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.storeanalytics.store.service.StoreDataFreshnessStatus;
import java.time.LocalDate;

public record StorePlanProgressDataQuality(
        StoreDataFreshnessStatus freshnessStatus,
        @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate dataThroughDate,
        boolean completeThroughAsOf,
        boolean classificationComplete,
        long unmappedItemCount,
        long openQualityIssueCount
) {
}
