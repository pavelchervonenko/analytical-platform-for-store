package com.storeanalytics.notification.daily;

public record DailyStorePulsePlanningResult(
        int eligibleStores,
        int createdEvents,
        int existingEvents
) {
}
