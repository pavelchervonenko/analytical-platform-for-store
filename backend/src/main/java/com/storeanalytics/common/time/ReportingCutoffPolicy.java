package com.storeanalytics.common.time;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import java.time.LocalDate;
import java.time.YearMonth;

public final class ReportingCutoffPolicy {

    private ReportingCutoffPolicy() {
    }

    public static LocalDate clampToCompletedDay(
            YearMonth month,
            LocalDate requestedAsOf,
            LocalDate expectedThroughDate
    ) {
        YearMonth validatedMonth = requireNonNull(month, "month");
        LocalDate validatedAsOf = requireNonNull(requestedAsOf, "asOf");
        if (expectedThroughDate == null
                || !YearMonth.from(expectedThroughDate).equals(validatedMonth)
                || !validatedAsOf.isAfter(expectedThroughDate)) {
            return validatedAsOf;
        }
        return expectedThroughDate;
    }
}
