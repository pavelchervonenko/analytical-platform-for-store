package com.storeanalytics.sync.service;

import com.storeanalytics.integration.livesklad.dto.LiveSkladSaleDetailPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladSaleSummaryPayload;
import java.util.Objects;

record LiveSkladSaleSource(
        LiveSkladSaleSummaryPayload summary,
        LiveSkladSaleDetailPayload detail
) {

    LiveSkladSaleSource {
        Objects.requireNonNull(summary, "summary must not be null");
        Objects.requireNonNull(detail, "detail must not be null");
        if (!summary.externalId().equals(detail.externalId())) {
            throw new IllegalArgumentException("sale summary and detail IDs must match");
        }
    }
}
