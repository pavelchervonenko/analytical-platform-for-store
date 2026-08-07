package com.storeanalytics.notification.fanout;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.notification.daily.DailyStorePulsePayload;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DailyTelegramMessageRendererTest {

    private final DailyTelegramMessageRenderer renderer =
            new DailyTelegramMessageRenderer();

    @Test
    void rendersBackendFactsWithoutInvokingAnLlm() {
        DailyNotificationEvent event = new DailyNotificationEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Магазин на площади",
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
                metric("125000.00", "12.5"),
                metric("25000", "5.0"),
                metric("30000.00", "-3.2"),
                metric("7500", null),
                List.of(named("PHONE", "Телефоны", "90000.00", "10.0")),
                List.of(named("employee", "Анна", "70000.00", "15.0")),
                new DailyStorePulsePayload.Quality(false, 2)
        );

        RenderedTelegramMessage result = renderer.render(event, payload);

        assertThat(result.text())
                .startsWith("☀️ УТРО · СВОДКА")
                .contains("02.08.2026 · сравнение с 01.08.2026")
                .contains("• Выручка — 125 000 ₽ · ↑ 12,5% ко вчера")
                .contains("Телефоны — 90 000 ₽")
                .contains("Анна — 70 000 ₽")
                .contains("ограничения качества данных");
        assertThat(result.contentHash()).matches("[a-f0-9]{64}");
    }

    private DailyStorePulsePayload.Metric metric(String value, String change) {
        return new DailyStorePulsePayload.Metric(
                new BigDecimal(value),
                change == null ? null : new BigDecimal(change)
        );
    }

    private DailyStorePulsePayload.NamedMetric named(
            String code,
            String name,
            String value,
            String change
    ) {
        return new DailyStorePulsePayload.NamedMetric(
                code,
                name,
                new BigDecimal(value),
                change == null ? null : new BigDecimal(change)
        );
    }
}
