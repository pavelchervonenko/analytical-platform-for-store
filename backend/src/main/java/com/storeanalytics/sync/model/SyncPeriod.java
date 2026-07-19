package com.storeanalytics.sync.model;

import static com.storeanalytics.common.validation.ModelValidation.require;

import java.time.Instant;

public record SyncPeriod(Instant start, Instant end) {

    public SyncPeriod {
        require(end == null || start == null || !end.isBefore(start),
                "period end must not be before period start");
    }
}
