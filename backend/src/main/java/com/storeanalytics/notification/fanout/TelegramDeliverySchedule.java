package com.storeanalytics.notification.fanout;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import java.time.Instant;

public record TelegramDeliverySchedule(Instant scheduledAt, boolean expired) {

    public TelegramDeliverySchedule {
        requireNonNull(scheduledAt, "scheduledAt");
    }
}
