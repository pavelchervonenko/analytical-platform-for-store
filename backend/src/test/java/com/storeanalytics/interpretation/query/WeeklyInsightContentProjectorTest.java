package com.storeanalytics.interpretation.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.CandidateKind;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.CandidateSignal;
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

    @Test
    void prefersBackendPrioritizedCandidateOverProviderOrder() throws Exception {
        UUID employeeId = UUID.randomUUID();
        JsonNode content = JsonMapper.builder().build().readTree("""
                {
                  "employees": [{
                    "employeeRef": "E01",
                    "analysisStatus": "SUFFICIENT"
                  }],
                  "summaryBlocks": [{
                    "scope": "STORE",
                    "employeeRef": null,
                    "section": "HEADLINE",
                    "text": "Итог магазина",
                    "evidenceRefs": ["STORE.REVENUE.CURRENT"]
                  }, {
                    "scope": "TEAM",
                    "employeeRef": null,
                    "section": "TEAM_OVERVIEW",
                    "text": "Итог команды",
                    "evidenceRefs": ["TEAM.EMPLOYEES.CURRENT"]
                  }, {
                    "scope": "EMPLOYEE",
                    "employeeRef": "E01",
                    "section": "HEADLINE",
                    "text": "Итог сотрудника",
                    "evidenceRefs": ["EMPLOYEE.E01.REVENUE.CURRENT"]
                  }],
                  "insights": [{
                    "scope": "STORE",
                    "employeeRef": null,
                    "kind": "OPPORTUNITY",
                    "theme": "OTHER",
                    "candidateRef": null,
                    "title": "Общий вывод",
                    "summary": "Не должен стать главным только из-за порядка.",
                    "evidenceRefs": ["STORE.REVENUE.CURRENT"]
                  }, {
                    "scope": "STORE",
                    "employeeRef": null,
                    "kind": "OPPORTUNITY",
                    "theme": "CATEGORY_MIX",
                    "candidateRef": "C002",
                    "title": "Категория",
                    "summary": "Категорийный вывод.",
                    "evidenceRefs": ["STORE.CATEGORY.CURRENT"]
                  }, {
                    "scope": "STORE",
                    "employeeRef": null,
                    "kind": "OPPORTUNITY",
                    "theme": "PROFITABILITY",
                    "candidateRef": "C001",
                    "title": "Вал",
                    "summary": "Приоритетный вывод по валовой прибыли.",
                    "evidenceRefs": ["STORE.GROSS_PROFIT.CURRENT"]
                  }],
                  "actions": [],
                  "teamRelationships": [],
                  "dataLimitations": []
                }
                """);
        WeeklyInterpretationDetailView interpretation =
                new WeeklyInterpretationDetailView(summary(2), content, List.of());
        List<CandidateSignal> candidates = List.of(
                new CandidateSignal(
                        "C001", CandidateKind.OPPORTUNITY, "PROFITABILITY",
                        null, List.of("STORE.GROSS_PROFIT.CURRENT")
                ),
                new CandidateSignal(
                        "C002", CandidateKind.OPPORTUNITY, "CATEGORY_MIX",
                        null, List.of("STORE.CATEGORY.CURRENT")
                )
        );

        WeeklyInsightContentView result = new WeeklyInsightContentProjector().project(
                interpretation,
                snapshot(employeeId, candidates)
        );

        assertThat(result.store().path("strength").path("title").asText())
                .isEqualTo("Вал");
    }

    @Test
    void projectsV3PrimarySignalOnceAndKeepsSecondaryRiskSeparate()
            throws Exception {
        UUID employeeId = UUID.randomUUID();
        JsonNode content = JsonMapper.builder().build().readTree("""
                {
                  "employees": [{
                    "employeeRef": "E01",
                    "analysisStatus": "SUFFICIENT"
                  }],
                  "primarySignal": {
                    "scope": "STORE",
                    "employeeRef": null,
                    "categoryCode": null,
                    "kind": "RISK",
                    "theme": "PLAN",
                    "candidateRef": "C001",
                    "text": "Главное отклонение связано с выполнением плана.",
                    "evidenceRefs": ["STORE.PLAN.CURRENT"]
                  },
                  "summaryBlocks": [{
                    "scope": "TEAM",
                    "employeeRef": null,
                    "section": "TEAM_OVERVIEW",
                    "categoryCode": null,
                    "text": "Итог команды",
                    "evidenceRefs": ["TEAM.EMPLOYEES.CURRENT"]
                  }, {
                    "scope": "EMPLOYEE",
                    "employeeRef": "E01",
                    "section": "HEADLINE",
                    "categoryCode": null,
                    "text": "Итог сотрудника",
                    "evidenceRefs": ["EMPLOYEE.E01.REVENUE.CURRENT"]
                  }],
                  "insights": [{
                    "scope": "STORE",
                    "employeeRef": null,
                    "categoryCode": "ACCESSORY",
                    "kind": "RISK",
                    "theme": "CATEGORY_MIX",
                    "candidateRef": "C002",
                    "title": "Снижение категории",
                    "summary": "Категория требует отдельной проверки.",
                    "evidenceRefs": ["STORE.CATEGORY.CURRENT"]
                  }],
                  "actions": [],
                  "teamRelationships": [],
                  "dataLimitations": []
                }
                """);
        List<CandidateSignal> candidates = List.of(
                new CandidateSignal(
                        "C001", CandidateKind.RISK, "PLAN",
                        null, List.of("STORE.PLAN.CURRENT")
                ),
                new CandidateSignal(
                        "C002", CandidateKind.RISK, "CATEGORY_MIX",
                        null, List.of("STORE.CATEGORY.CURRENT")
                )
        );

        WeeklyInsightContentView result = new WeeklyInsightContentProjector().project(
                new WeeklyInterpretationDetailView(
                        summary(3), content, List.of()
                ),
                snapshot(employeeId, candidates)
        );

        assertThat(result.store().path("headline").path("text").asText())
                .isEqualTo("Главное отклонение связано с выполнением плана.");
        assertThat(result.store().path("primaryRisk").path("candidateRef").asText())
                .isEqualTo("C002");
        assertThat(result.store().toString())
                .containsOnlyOnce("Главное отклонение связано с выполнением плана.");
    }

    @Test
    void projectsBackendOwnedNeutralHeadlineWhenV3HasNoPrimarySignal()
            throws Exception {
        UUID employeeId = UUID.randomUUID();
        JsonNode content = JsonMapper.builder().build().readTree("""
                {
                  "employees": [{
                    "employeeRef": "E01",
                    "analysisStatus": "SUFFICIENT"
                  }],
                  "primarySignal": null,
                  "summaryBlocks": [{
                    "scope": "TEAM",
                    "employeeRef": null,
                    "section": "TEAM_OVERVIEW",
                    "categoryCode": null,
                    "text": "Итог команды",
                    "evidenceRefs": ["TEAM.EMPLOYEES.CURRENT"]
                  }, {
                    "scope": "EMPLOYEE",
                    "employeeRef": "E01",
                    "section": "HEADLINE",
                    "categoryCode": null,
                    "text": "Итог сотрудника",
                    "evidenceRefs": ["EMPLOYEE.E01.REVENUE.CURRENT"]
                  }],
                  "insights": [],
                  "actions": [],
                  "teamRelationships": [],
                  "dataLimitations": []
                }
                """);

        WeeklyInsightContentView result = new WeeklyInsightContentProjector().project(
                new WeeklyInterpretationDetailView(
                        summary(3), content, List.of()
                ),
                snapshot(employeeId)
        );

        assertThat(result.store().path("headline").path("text").asText())
                .isEqualTo(WeeklyInsightV3ContentProjector.NEUTRAL_HEADLINE);
        assertThat(result.store().path("headline").path("evidenceRefs"))
                .isEmpty();
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
        return snapshot(employeeId, List.of());
    }

    private PersistedWeeklySnapshot snapshot(
            UUID employeeId,
            List<CandidateSignal> candidates
    ) {
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
                                List.of("E01"), List.of(), candidates.stream()
                                .map(CandidateSignal::candidateRef).toList(), List.of(),
                                List.of(), List.of()
                        ),
                        new Facts(List.of(), List.of(), List.of(), candidates)
                ),
                "a".repeat(64),
                List.of(new SnapshotEmployeeMembership(
                        employeeId, "E01", "Ирина"
                )),
                Instant.parse("2026-08-03T05:00:00Z")
        );
    }
}
