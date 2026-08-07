package com.storeanalytics.interpretation.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Facts;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Manifest;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.QualityStatus;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Versions;
import com.storeanalytics.interpretation.generation.LlmAnalysisTriggerType;
import com.storeanalytics.interpretation.snapshot.PersistedWeeklySnapshot;
import com.storeanalytics.interpretation.snapshot.SnapshotEmployeeMembership;
import com.storeanalytics.interpretation.snapshot.WeeklyAnalyticsFactsQuery;
import com.storeanalytics.interpretation.snapshot.WeeklySnapshotPayload;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class WeeklyInsightContentProjectorTest {

    @Test
    void replacesEmployeeReferenceWithImmutableIdentityAndName() throws Exception {
        UUID employeeId = UUID.randomUUID();
        JsonNode content = JsonMapper.builder().build().readTree("""
                {
                  "store": {},
                  "teamInsights": {},
                  "employees": [{
                    "employeeRef": "E01",
                    "analysisStatus": "SUFFICIENT",
                    "headline": {"text": "Стабильный результат"}
                  }],
                  "dataLimitations": []
                }
                """);
        WeeklyInterpretationDetailView interpretation =
                new WeeklyInterpretationDetailView(null, content, List.of());

        WeeklyInsightContentView result = new WeeklyInsightContentProjector().project(
                interpretation,
                snapshot(employeeId)
        );

        assertThat(result.employees()).singleElement().satisfies(employee -> {
            assertThat(employee.employeeId()).isEqualTo(employeeId);
            assertThat(employee.displayName()).isEqualTo("Ирина");
            assertThat(employee.analysisStatus()).isEqualTo("SUFFICIENT");
            assertThat(employee.insight().has("employeeRef")).isFalse();
        });
        assertThat(content.path("employees").get(0).path("employeeRef").textValue())
                .isEqualTo("E01");
    }

    @Test
    void adaptsFlatV2ContentWithoutExposingEmployeeReferences() throws Exception {
        UUID employeeId = UUID.randomUUID();
        JsonNode content = JsonMapper.builder().build().readTree(
                getClass().getResourceAsStream(
                        "/contracts/llm/examples/"
                                + "weekly-interpretation-content-v2-ready.json"
                )
        );
        WeeklyInterpretationDetailView interpretation =
                new WeeklyInterpretationDetailView(
                        summary(2), content, List.of()
                );

        WeeklyInsightContentView result = new WeeklyInsightContentProjector().project(
                interpretation,
                snapshot(employeeId)
        );

        assertThat(result.store().path("headline").path("text").asText())
                .contains("структура продаж");
        assertThat(result.store().path("resultSummary").isNull()).isTrue();
        assertThat(result.store().path("recommendedActions")).hasSize(1);
        assertThat(result.teamInsights().path("competencyLeaders")).hasSize(1);
        assertThat(result.teamInsights().path("competencyLeaders").get(0)
                .path("employeeNames").get(0).asText()).isEqualTo("Ирина");
        assertThat(result.employees()).singleElement().satisfies(employee -> {
            assertThat(employee.employeeId()).isEqualTo(employeeId);
            assertThat(employee.insight().has("employeeRef")).isFalse();
            assertThat(employee.insight().path("categoryPerformance")
                    .path("summary").path("text").asText())
                    .contains("Сервисное направление");
        });
    }

    private WeeklyInterpretationSummaryView summary(int contentSchemaVersion) {
        return new WeeklyInterpretationSummaryView(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                LocalDate.of(2026, 7, 27),
                LocalDate.of(2026, 8, 2),
                "Europe/Moscow",
                1,
                1,
                true,
                null,
                LlmAnalysisTriggerType.INITIAL,
                "a".repeat(64),
                contentSchemaVersion,
                QualityStatus.READY,
                1,
                Instant.parse("2026-08-03T05:00:00Z"),
                Instant.parse("2026-08-03T05:00:01Z")
        );
    }

    private PersistedWeeklySnapshot snapshot(UUID employeeId) {
        UUID storeId = UUID.randomUUID();
        LocalDate start = LocalDate.of(2026, 7, 27);
        LocalDate end = LocalDate.of(2026, 8, 2);
        return new PersistedWeeklySnapshot(
                UUID.randomUUID(),
                storeId,
                new WeeklyAnalyticsFactsQuery(
                        storeId,
                        new StoreKpiPeriod(start, end),
                        new StoreKpiPeriod(start.minusWeeks(1), end.minusWeeks(1))
                ),
                "Europe/Moscow",
                1,
                null,
                "INITIAL",
                null,
                UUID.randomUUID(),
                Instant.parse("2026-08-03T05:00:00Z"),
                Instant.parse("2026-08-03T05:00:00Z"),
                QualityStatus.READY,
                new Versions(1, "metrics-v1", "calculation-v1", "quality-v1"),
                new WeeklySnapshotPayload(
                        1,
                        new Manifest(
                                List.of("E01"), List.of(), List.of(), List.of(),
                                List.of(), List.of()
                        ),
                        new Facts(List.of(), List.of(), List.of(), List.of())
                ),
                "a".repeat(64),
                List.of(new SnapshotEmployeeMembership(
                        employeeId, "E01", "Ирина"
                )),
                Instant.parse("2026-08-03T05:00:00Z")
        );
    }
}
