package com.storeanalytics.notification.fanout;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import java.util.UUID;

public record NotificationFanoutResult(
        UUID eventId,
        NotificationFanoutOutcome outcome,
        int recipientCount,
        int deliveryCount
) {

    public NotificationFanoutResult {
        requireNonNull(eventId, "eventId");
        requireNonNull(outcome, "outcome");
        require(recipientCount >= 0, "recipientCount must not be negative");
        require(deliveryCount >= 0, "deliveryCount must not be negative");
        require(deliveryCount <= recipientCount,
                "deliveryCount must not exceed recipientCount");
    }
}
