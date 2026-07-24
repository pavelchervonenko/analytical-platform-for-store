package com.storeanalytics.performance.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record EmployeeRatingSettingRequest(
        @NotNull Boolean participatesInRanking,
        @NotNull @PositiveOrZero Long version
) {
}
