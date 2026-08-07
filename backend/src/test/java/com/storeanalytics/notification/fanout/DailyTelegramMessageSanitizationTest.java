package com.storeanalytics.notification.fanout;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.notification.daily.DailyStorePulsePayload;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DailyTelegramMessageSanitizationTest {

    @Test
    void collapsesControlWhitespaceInExternalDisplayNames() {
        DailyNotificationEvent event = new DailyNotificationEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Магазин\nложный заголовок",
                "DAILY_STORE_PULSE",
                "{}",
                "a".repeat(64),
                Instant.parse("2026-08-03T05:05:00Z"),
                Instant.parse("2026-08-03T11:00:00Z")
        );
        DailyStorePulsePayload payload = new DailyStorePulsePayload(
                1,
                LocalDate.of(2026, 8, 2),
                LocalDate.of(2026, 8, 1),
                metric(),
                metric(),
                metric(),
                metric(),
                List.of(named("Телефоны\tфлаг")),
                List.of(named("Анна\nинъекция")),
                new DailyStorePulsePayload.Quality(true, 0)
        );

        String text = new DailyTelegramMessageRenderer().render(event, payload).text();

        assertThat(text)
                .contains("Магазин ложный заголовок")
                .contains("Телефоны флаг")
                .contains("Анна инъекция")
                .doesNotContain("Магазин\nложный", "Анна\nинъекция");
    }

    private DailyStorePulsePayload.Metric metric() {
        return new DailyStorePulsePayload.Metric(BigDecimal.ONE, null);
    }

    private DailyStorePulsePayload.NamedMetric named(String name) {
        return new DailyStorePulsePayload.NamedMetric(
                UUID.randomUUID().toString(), name, BigDecimal.ONE, null
        );
    }
}
