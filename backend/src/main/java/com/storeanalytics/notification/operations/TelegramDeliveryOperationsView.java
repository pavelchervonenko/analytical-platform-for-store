package com.storeanalytics.notification.operations;

import java.time.Instant;
import java.util.List;

public record TelegramDeliveryOperationsView(
        Instant generatedAt,
        TelegramDeliveryQueueSummary summary,
        List<TelegramDeliveryIncidentView> incidents
) {

    public TelegramDeliveryOperationsView {
        incidents = List.copyOf(incidents);
    }
}
