package com.storeanalytics.notification.fanout;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TelegramQuietHoursSchedulerTest {

    private final TelegramQuietHoursScheduler scheduler =
            new TelegramQuietHoursScheduler();

    @Test
    void movesNightNotificationToNextAllowedLocalTime() {
        Instant earliest = Instant.parse("2026-08-03T20:30:00Z");

        TelegramDeliverySchedule result = scheduler.schedule(
                earliest,
                Instant.parse("2026-08-04T20:30:00Z"),
                recipient(true, LocalTime.of(21, 0), LocalTime.of(8, 0))
        );

        assertThat(result.expired()).isFalse();
        assertThat(result.scheduledAt())
                .isEqualTo(Instant.parse("2026-08-04T06:00:00Z"));
    }

    @Test
    void marksDeliveryExpiredWhenQuietHoursOutliveEvent() {
        Instant earliest = Instant.parse("2026-08-03T20:30:00Z");

        TelegramDeliverySchedule result = scheduler.schedule(
                earliest,
                Instant.parse("2026-08-03T22:00:00Z"),
                recipient(true, LocalTime.of(21, 0), LocalTime.of(8, 0))
        );

        assertThat(result.expired()).isTrue();
        assertThat(result.scheduledAt()).isEqualTo(earliest);
    }

    @Test
    void doesNotDelayWhenQuietHoursAreDisabled() {
        Instant earliest = Instant.parse("2026-08-03T20:30:00Z");

        TelegramDeliverySchedule result = scheduler.schedule(
                earliest,
                Instant.parse("2026-08-04T20:30:00Z"),
                recipient(false, LocalTime.of(21, 0), LocalTime.of(8, 0))
        );

        assertThat(result.expired()).isFalse();
        assertThat(result.scheduledAt()).isEqualTo(earliest);
    }

    private TelegramNotificationRecipient recipient(
            boolean quietHours,
            LocalTime start,
            LocalTime end
    ) {
        return new TelegramNotificationRecipient(
                UUID.randomUUID(),
                UUID.randomUUID(),
                ZoneId.of("Europe/Kaliningrad"),
                quietHours,
                start,
                end
        );
    }
}
