package com.storeanalytics.metrics.service;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.common.exception.InvalidRequestException;
import java.time.LocalDate;

public record StoreKpiPeriod(LocalDate start, LocalDate end) {

    public static final int MAXIMUM_INCLUSIVE_DAYS = 366;

    public StoreKpiPeriod {
        requireNonNull(start, "periodStart");
        requireNonNull(end, "periodEnd");
        if (end.isBefore(start)) {
            throw new InvalidRequestException(
                    "periodEnd must not be before periodStart"
            );
        }
        if (end.toEpochDay() - start.toEpochDay() >= MAXIMUM_INCLUSIVE_DAYS) {
            throw new InvalidRequestException(
                    "analytics period must not exceed 366 inclusive days"
            );
        }
    }
}
