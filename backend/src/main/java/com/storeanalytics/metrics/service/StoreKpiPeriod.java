package com.storeanalytics.metrics.service;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import java.time.LocalDate;

public record StoreKpiPeriod(LocalDate start, LocalDate end) {

    public StoreKpiPeriod {
        requireNonNull(start, "periodStart");
        requireNonNull(end, "periodEnd");
        require(!end.isBefore(start), "periodEnd must not be before periodStart");
    }
}
