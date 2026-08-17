package com.storeanalytics.notification.fanout;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class WeeklyTelegramMessageRendererTest {

    private final WeeklyTelegramMessageRenderer renderer =
            new WeeklyTelegramMessageRenderer();

    @Test
    void rendersPhoneFirstHierarchyUsingSnapshotNames() throws IOException {
        JsonNode content = content(
                "weekly-interpretation-content-v1-ready.json"
        );

        RenderedTelegramMessage result = renderer.render(
                event(false),
                content,
                Map.of("E01", "Анна", "E02", "Борис")
        );

        assertThat(result.text())
                .startsWith("📊 НЕДЕЛЯ · ОТЧЁТ ГОТОВ")
                .contains("✨ ГЛАВНОЕ")
                .contains("📈 РЕЗУЛЬТАТ И ДИНАМИКА")
                .contains("🔎 ФОКУС")
                .contains("🎯 ДЕЙСТВИЯ НА НЕДЕЛЮ")
                .contains("👥 КОМАНДА")
                .contains("СОТРУДНИКИ")
                .contains("ПОЛНЫЙ РАЗБОР")
                .contains("Анна")
                .contains("Борис")
                .contains("1. Сфокусироваться на структуре продаж")
                .doesNotContain("E01")
                .doesNotContain("evidenceRefs");
        assertThat(result.text().codePointCount(0, result.text().length()))
                .isLessThanOrEqualTo(4096);
        assertThat(result.contentHash()).matches("[a-f0-9]{64}");
    }

    @Test
    void rendersFlatV2WithoutCodesOrEmptySections() throws IOException {
        JsonNode content = content(
                "weekly-interpretation-content-v2-ready.json"
        );

        RenderedTelegramMessage result = renderer.render(
                event(false, 2),
                content,
                Map.of("E01", "Анна")
        );

        assertThat(result.text())
                .contains("версия 1")
                .contains("Анна")
                .contains("⭐ Анна")
                .contains("Распространить сильную практику")
                .contains("на этой неделе")
                .doesNotContain("РЕЗУЛЬТАТ И ДИНАМИКА")
                .doesNotContain("🔎 ФОКУС")
                .doesNotContain("E01")
                .doesNotContain("SERVICE_SALES")
                .doesNotContain("evidenceRefs");
        assertThat(result.text().codePointCount(0, result.text().length()))
                .isLessThanOrEqualTo(4096);
    }

    @Test
    void rendersV3PrimarySignalExactlyOnce() throws IOException {
        JsonNode content = content(
                "weekly-interpretation-content-v3-ready.json"
        );

        RenderedTelegramMessage result = renderer.render(
                event(false, 3),
                content,
                Map.of("E01", "Анна")
        );

        assertThat(result.text())
                .contains("✨ ГЛАВНОЕ")
                .containsOnlyOnce(
                        "Главным подтверждённым изменением стало "
                                + "улучшение результата магазина."
                )
                .contains("Анна")
                .doesNotContain("C001")
                .doesNotContain("evidenceRefs");
    }
    @Test
    void neverLeaksEmployeeReferenceWhenSnapshotNameIsMissing()
            throws IOException {
        JsonNode content = content(
                "weekly-interpretation-content-v2-ready.json"
        );

        RenderedTelegramMessage result = renderer.render(
                event(false, 2),
                content,
                Map.of()
        );

        assertThat(result.text())
                .contains("Сотрудник")
                .doesNotContain("E01");
    }

    @Test
    void rendersDuplicateLimitationsOnlyOnce() throws IOException {
        JsonNode content = JsonMapper.builder().build().readTree(
                """
                {
                  "summaryBlocks": [],
                  "insights": [],
                  "actions": [],
                  "teamRelationships": [],
                  "employees": [],
                  "dataLimitations": [
                    {"summary": "Качество данных снижает уверенность."},
                    {"summary": "  качество данных снижает уверенность.  "},
                    {"summary": "Часть выводов требует проверки."}
                  ]
                }
                """
        );

        RenderedTelegramMessage result = renderer.render(
                event(false, 2),
                content,
                Map.of()
        );

        assertThat(result.text())
                .contains("⚠️ ЧТО ВАЖНО УЧЕСТЬ")
                .contains("Часть выводов требует проверки.")
                .doesNotContain("Остальные ограничения подробно описаны");
        assertThat(result.text().toLowerCase().split(
                "качество данных снижает уверенность\\.",
                -1
        )).hasSize(2);
    }

    @Test
    void revisionUsesDistinctHeadingAndVersion() throws IOException {
        JsonNode content = content(
                "weekly-interpretation-content-v1-ready.json"
        );

        RenderedTelegramMessage result = renderer.render(
                event(true),
                content,
                Map.of("E01", "Анна", "E02", "Борис")
        );

        assertThat(result.text())
                .startsWith("📊 НЕДЕЛЯ · ОТЧЁТ ОБНОВЛЁН")
                .contains("версия 2");
    }

    private JsonNode content(String name) throws IOException {
        return JsonMapper.builder().build().readTree(
                getClass().getResourceAsStream(
                        "/contracts/llm/examples/" + name
                )
        );
    }

    private WeeklyNotificationEvent event(boolean revised) {
        return event(revised, 1);
    }

    private WeeklyNotificationEvent event(
            boolean revised,
            int contentSchemaVersion
    ) {
        return new WeeklyNotificationEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Магазин на Победы",
                revised ? "WEEKLY_REPORT_REVISED" : "WEEKLY_REPORT_READY",
                UUID.randomUUID(),
                UUID.randomUUID(),
                revised ? 2 : 1,
                contentSchemaVersion,
                LocalDate.parse("2026-07-27"),
                LocalDate.parse("2026-08-02"),
                "{}",
                "a".repeat(64),
                Instant.parse("2026-08-03T06:00:00Z"),
                Instant.parse("2026-08-04T06:00:00Z")
        );
    }
}
