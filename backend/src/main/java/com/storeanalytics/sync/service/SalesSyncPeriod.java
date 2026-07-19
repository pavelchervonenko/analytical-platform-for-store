package com.storeanalytics.sync.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public record SalesSyncPeriod(Instant start, Instant end) {

    private static final Duration MAX_PERIOD = Duration.ofDays(31);

    public SalesSyncPeriod {
        Objects.requireNonNull(start, "start must not be null");
        Objects.requireNonNull(end, "end must not be null");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("sales period end must be after start");
        }
        if (Duration.between(start, end).compareTo(MAX_PERIOD) > 0) {
            throw new IllegalArgumentException("sales period must not exceed 31 days");
        }
    }
}
