package com.storeanalytics.notification.daily;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DailyStorePulsePlannerTest {

    @Test
    void createsYesterdayEventOnlyWhenBothSourcesCoverTheDay() {
        UUID storeId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-03T05:10:00Z");
        DailyStorePulseEventStore store = mock(DailyStorePulseEventStore.class);
        DailyStorePulseProjection projection = mock(DailyStorePulseProjection.class);
        DailyStorePulseMetrics metrics = mock(DailyStorePulseMetrics.class);
        DailyStorePulsePayload payload = mock(DailyStorePulsePayload.class);
        DailyStorePulseEventStore.StoreTarget target = new DailyStorePulseEventStore.StoreTarget(
                storeId,
                "Магазин",
                "Europe/Moscow",
                Instant.parse("2026-08-03T00:00:00Z"),
                Instant.parse("2026-08-03T00:00:00Z")
        );
        when(store.activeStores()).thenReturn(List.of(target));
        when(projection.build(storeId, LocalDate.parse("2026-08-02")))
                .thenReturn(payload);
        when(store.insert(
                target,
                payload,
                "daily-store-pulse-v1",
                now,
                Instant.parse("2026-08-03T11:00:00Z")
        )).thenReturn(true);

        DailyStorePulsePlanningResult result = planner(
                store, projection, metrics, now
        ).plan();

        assertThat(result).isEqualTo(new DailyStorePulsePlanningResult(1, 1, 0));
        verify(metrics).created(now);
    }

    @Test
    void skipsStoreWhenReturnsCoverageIsIncomplete() {
        UUID storeId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-03T05:10:00Z");
        DailyStorePulseEventStore store = mock(DailyStorePulseEventStore.class);
        DailyStorePulseProjection projection = mock(DailyStorePulseProjection.class);
        DailyStorePulseMetrics metrics = mock(DailyStorePulseMetrics.class);
        when(store.activeStores()).thenReturn(List.of(
                new DailyStorePulseEventStore.StoreTarget(
                        storeId,
                        "Магазин",
                        "Europe/Moscow",
                        Instant.parse("2026-08-03T00:00:00Z"),
                        Instant.parse("2026-08-01T21:00:00Z")
                )
        ));

        DailyStorePulsePlanningResult result = planner(
                store, projection, metrics, now
        ).plan();

        assertThat(result).isEqualTo(new DailyStorePulsePlanningResult(0, 0, 0));
        verify(projection, never()).build(storeId, LocalDate.parse("2026-08-02"));
    }

    private DailyStorePulsePlanner planner(
            DailyStorePulseEventStore store,
            DailyStorePulseProjection projection,
            DailyStorePulseMetrics metrics,
            Instant now
    ) {
        return new DailyStorePulsePlanner(
                store,
                projection,
                new DailyStorePulseProperties(
                        true,
                        Duration.ofMinutes(5),
                        LocalTime.parse("08:05"),
                        LocalTime.parse("14:00"),
                        "daily-store-pulse-v1",
                        "daily-store-pulse-v1"
                ),
                metrics,
                Clock.fixed(now, ZoneOffset.UTC)
        );
    }
}
