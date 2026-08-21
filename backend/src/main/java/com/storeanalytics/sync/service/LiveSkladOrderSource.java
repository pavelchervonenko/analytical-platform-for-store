package com.storeanalytics.sync.service;

import com.storeanalytics.integration.livesklad.dto.LiveSkladOrderDetailPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladOrderSummaryPayload;
import java.util.Objects;

record LiveSkladOrderSource(
        LiveSkladOrderSummaryPayload summary,
        LiveSkladOrderDetailPayload detail
) {

    LiveSkladOrderSource {
        Objects.requireNonNull(summary, "summary must not be null");
        if (detail != null
                && !summary.externalId().equals(detail.externalId())) {
            throw new IllegalArgumentException(
                    "order summary and detail IDs must match"
            );
        }
    }

    static LiveSkladOrderSource fromDetail(
            LiveSkladOrderDetailPayload detail
    ) {
        Objects.requireNonNull(detail, "detail must not be null");
        return new LiveSkladOrderSource(
                new LiveSkladOrderSummaryPayload(
                        detail.externalId(),
                        detail.documentNumber(),
                        detail.createdAt(),
                        detail.visible(),
                        detail.statusExternalId(),
                        detail.statusName(),
                        detail.storeExternalId(),
                        detail.rawPayload()
                ),
                detail
        );
    }
}
