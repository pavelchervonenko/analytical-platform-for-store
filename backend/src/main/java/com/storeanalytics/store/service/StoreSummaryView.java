package com.storeanalytics.store.service;

import java.time.LocalTime;
import java.util.UUID;

public record StoreSummaryView(
        UUID id,
        String name,
        String address,
        String timezone,
        LocalTime businessDayStart,
        LocalTime opensAt,
        LocalTime closesAt,
        boolean active
) {
}
