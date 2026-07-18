package com.storeanalytics.store.model;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;

public record StoreSchedule(
        String timezone,
        LocalTime businessDayStart,
        LocalTime opensAt,
        LocalTime closesAt
) {

    public StoreSchedule {
        timezone = requireText(timezone, "timezone");
        try {
            ZoneId.of(timezone);
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("timezone must be a valid ZoneId", exception);
        }
        businessDayStart = requireNonNull(businessDayStart, "businessDayStart");
        opensAt = requireNonNull(opensAt, "opensAt");
        closesAt = requireNonNull(closesAt, "closesAt");
        require(opensAt.isBefore(closesAt), "opensAt must be before closesAt");
    }
}
