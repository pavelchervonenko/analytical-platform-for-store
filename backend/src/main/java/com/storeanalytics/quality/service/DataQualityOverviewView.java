package com.storeanalytics.quality.service;

import java.time.Instant;
import java.util.List;

public record DataQualityOverviewView(
        Instant checkedAt,
        int storeCount,
        int okStoreCount,
        int warningStoreCount,
        int errorStoreCount,
        int openIssueCount,
        List<StoreDataQualitySummaryView> stores
) {
}
