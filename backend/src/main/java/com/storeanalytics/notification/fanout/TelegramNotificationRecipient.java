package com.storeanalytics.notification.fanout;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

public record TelegramNotificationRecipient(
        UUID userId,
        UUID subscriptionId,
        ZoneId deliveryZone,
        boolean quietHoursEnabled,
        LocalTime quietHoursStart,
        LocalTime quietHoursEnd
) {

    public TelegramNotificationRecipient {
        requireNonNull(userId, "userId");
        requireNonNull(subscriptionId, "subscriptionId");
        requireNonNull(deliveryZone, "deliveryZone");
        requireNonNull(quietHoursStart, "quietHoursStart");
        requireNonNull(quietHoursEnd, "quietHoursEnd");
    }
}
