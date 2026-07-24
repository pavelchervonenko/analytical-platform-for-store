package com.storeanalytics.quality.service;

import com.fasterxml.jackson.annotation.JsonFormat;

import com.storeanalytics.store.service.StoreDataFreshnessStatus;
import java.time.LocalDate;

public record PeriodSourceDataQualityView(
        StoreDataFreshnessStatus freshnessStatus,
        @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate dataThroughDate,
        boolean completeThroughAsOf,
        boolean classificationComplete,
        boolean costDataComplete,
        long includedItemCount,
        long unmappedItemCount,
        long missingCostItemCount,
        long unexpectedZeroCostItemCount,
        long openQualityIssueCount
) {
}
