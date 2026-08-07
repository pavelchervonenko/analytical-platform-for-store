package com.storeanalytics.interpretation.query;

import java.time.LocalDate;

public record WeeklyInsightPeriodView(
        LocalDate periodStart,
        LocalDate periodEnd,
        String timezone
) {
}
