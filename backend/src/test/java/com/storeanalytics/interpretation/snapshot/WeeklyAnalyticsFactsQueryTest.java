package com.storeanalytics.interpretation.snapshot;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.metrics.service.StoreKpiPeriod;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WeeklyAnalyticsFactsQueryTest {

    @Test
    void acceptsAdjacentMondayToSundayPeriods() {
        new WeeklyAnalyticsFactsQuery(
                UUID.randomUUID(),
                period("2026-07-27", "2026-08-02"),
                period("2026-07-20", "2026-07-26")
        );
    }

    @Test
    void rejectsNonWeeklyOrNonAdjacentComparison() {
        UUID storeId = UUID.randomUUID();

        assertThatThrownBy(() -> new WeeklyAnalyticsFactsQuery(
                storeId,
                period("2026-07-28", "2026-08-03"),
                period("2026-07-21", "2026-07-27")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Monday-Sunday");

        assertThatThrownBy(() -> new WeeklyAnalyticsFactsQuery(
                storeId,
                period("2026-07-27", "2026-08-02"),
                period("2026-07-13", "2026-07-19")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("immediately preceding");
    }

    private StoreKpiPeriod period(String start, String end) {
        return new StoreKpiPeriod(LocalDate.parse(start), LocalDate.parse(end));
    }
}
