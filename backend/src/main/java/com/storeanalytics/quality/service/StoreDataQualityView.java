package com.storeanalytics.quality.service;

import com.storeanalytics.store.service.StoreDataStatusView;
import java.util.List;

public record StoreDataQualityView(
        StoreDataQualitySummaryView summary,
        StoreDataStatusView dataStatus,
        List<DataQualityIssueView> issues
) {
}
