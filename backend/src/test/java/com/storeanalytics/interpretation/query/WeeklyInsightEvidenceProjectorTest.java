package com.storeanalytics.interpretation.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Comparison;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.EmployeeFacts;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.EvidenceIndexEntry;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Fact;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Facts;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Manifest;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Materiality;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.QualityStatus;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Scope;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Sufficiency;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Unit;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Versions;
import com.storeanalytics.interpretation.snapshot.PersistedWeeklySnapshot;
import com.storeanalytics.interpretation.snapshot.SnapshotEmployeeMembership;
import com.storeanalytics.interpretation.snapshot.WeeklyAnalyticsFactsQuery;
import com.storeanalytics.interpretation.snapshot.WeeklySnapshotPayload;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class WeeklyInsightEvidenceProjectorTest {

    private static final String STORE_REVENUE =
            "STORE.NET_REVENUE.CURRENT";
    private static final String EMPLOYEE_CATEGORY =
            "EMP:E01.CATEGORY:CHARGER_CABLE.NET_REVENUE.CURRENT";
    private static final String UNAVAILABLE =
            "STORE.CLASSIFICATION_QUALITY.STATUS";
    private static final String PLAN_STATUS =
            "STORE.PLAN:ACCESSORY.STATUS";
    private static final String EMPLOYEE_RANK =
            "EMP:E01.RATING.RANK.CURRENT";

    private final WeeklyInsightEvidenceProjector projector =
            new WeeklyInsightEvidenceProjector();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void exposesOnlyOpaqueBackendFormattedEvidence() throws Exception {
        UUID employeeId = UUID.randomUUID();
        WeeklyInsightContentView content = content(
                STORE_REVENUE, EMPLOYEE_CATEGORY, UNAVAILABLE
        );

        WeeklyInsightContentView result = projector.project(
                content, snapshot(employeeId)
        );

        assertThat(result.evidence()).hasSize(5);
        assertThat(result.store().toString())
                .doesNotContain("STORE.", "EMP:E01");
        assertThat(result.teamInsights().toString())
                .doesNotContain("E01")
                .contains(employeeId.toString());
        assertThat(result.employees().getFirst().insight().toString())
                .doesNotContain("STORE.", "EMP:E01", "E01")
                .contains(employeeId.toString());
        assertThat(result.dataLimitations().toString())
                .doesNotContain("E01")
                .contains(employeeId.toString());
        assertThat(result.store().path("headline").path("evidenceRefs").get(0)
                .asText()).matches("EV\\d{3}");

        WeeklyInsightEvidenceView store = evidence(result, "Выручка");
        assertThat(store.formattedValue()).contains("120").endsWith("₽");
        assertThat(store.previousFormattedValue()).contains("100");
        assertThat(store.absoluteDeltaFormatted()).startsWith("+");
        assertThat(store.relativeDeltaFormatted()).isEqualTo("+20%");
        assertThat(store.comparisonText()).contains("Было", "изменение");
        assertThat(store.scope()).isEqualTo(Scope.STORE);
        assertThat(store.available()).isTrue();

        WeeklyInsightEvidenceView employee = result.evidence().stream()
                .filter(value -> employeeId.equals(value.employeeId()))
                .findFirst()
                .orElseThrow();
        assertThat(employee.displayName()).isEqualTo("Ирина");
        assertThat(employee.categoryLabel())
                .isEqualTo("Зарядные устройства и кабели");
        assertThat(employee.label())
                .contains("Ирина", "Зарядные устройства и кабели", "Выручка");

        WeeklyInsightEvidenceView unavailable = result.evidence().stream()
                .filter(value -> !value.available())
                .findFirst()
                .orElseThrow();
        assertThat(unavailable.label()).isEqualTo("Качество классификации");
        assertThat(unavailable.formattedValue()).isNull();
        assertThat(unavailable.sufficiency()).isNull();

        WeeklyInsightEvidenceView status = evidence(
                result, "План — Аксессуары · Статус плана"
        );
        assertThat(status.formattedValue()).isEqualTo("Есть риск невыполнения");

        WeeklyInsightEvidenceView rank = evidence(
                result, "Ирина · Место в рейтинге"
        );
        assertThat(rank.formattedValue()).isEqualTo("№ 2");
        assertThat(rank.previousFormattedValue()).isEqualTo("№ 3");
        assertThat(rank.absoluteDeltaFormatted()).isEqualTo("-1");
    }

    @Test
    void refusesEvidenceOutsidePublishedSnapshot() throws Exception {
        WeeklyInsightContentView content = content(
                "STORE.UNKNOWN.CURRENT", EMPLOYEE_CATEGORY, UNAVAILABLE
        );

        assertThatThrownBy(() -> projector.project(
                content, snapshot(UUID.randomUUID())
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside its snapshot");
    }

    private WeeklyInsightEvidenceView evidence(
            WeeklyInsightContentView content,
            String label
    ) {
        return content.evidence().stream()
                .filter(value -> value.label().equals(label))
                .findFirst()
                .orElseThrow();
    }

    private WeeklyInsightContentView content(
            String storeRef,
            String employeeRef,
            String limitationRef
    ) throws Exception {
        JsonNode store = jsonMapper.readTree("""
                {
                  "headline": {
                    "text": "Выручка выросла",
                    "evidenceRefs": ["%s", "%s"]
                  }
                }
                """.formatted(storeRef, PLAN_STATUS));
        JsonNode team = jsonMapper.readTree("""
                {
                  "summary": {"text": "Команда", "evidenceRefs": []},
                  "competencyLeaders": [{
                    "employeeRefs": ["E01"],
                    "evidenceRefs": []
                  }]
                }
                """);
        JsonNode employee = jsonMapper.readTree("""
                {
                  "headline": {
                    "text": "Категория выросла",
                    "evidenceRefs": ["%s", "%s"]
                  },
                  "action": {
                    "targetEmployeeRefs": ["E01"],
                    "evidenceRefs": []
                  }
                }
                """.formatted(employeeRef, EMPLOYEE_RANK));
        JsonNode limitations = jsonMapper.readTree("""
                [{
                  "summary": "Классификация ограничена",
                  "employeeRef": "E01",
                  "evidenceRefs": ["%s"]
                }]
                """.formatted(limitationRef));
        return new WeeklyInsightContentView(
                store,
                team,
                List.of(new WeeklyInsightEmployeeView(
                        UUID.randomUUID(), "Ирина", "SUFFICIENT", employee
                )),
                limitations
        );
    }

    private PersistedWeeklySnapshot snapshot(UUID employeeId) {
        UUID storeId = UUID.randomUUID();
        LocalDate start = LocalDate.of(2026, 7, 27);
        LocalDate end = LocalDate.of(2026, 8, 2);
        Fact storeRevenue = fact(
                STORE_REVENUE,
                "NET_REVENUE",
                null,
                Unit.MONEY,
                "120000.00",
                new Comparison(
                        new BigDecimal("100000.00"),
                        new BigDecimal("20000.00"),
                        new BigDecimal("20.00")
                )
        );
        Fact planStatus = new Fact(
                PLAN_STATUS,
                "PLAN_STATUS",
                null,
                Unit.STATUS,
                "AT_RISK",
                null,
                Sufficiency.SUFFICIENT,
                Materiality.CONTEXT
        );
        Fact employeeRank = fact(
                EMPLOYEE_RANK,
                "RATING_RANK",
                null,
                Unit.RANK,
                "2",
                new Comparison(
                        new BigDecimal("3"),
                        new BigDecimal("-1"),
                        null
                )
        );
        Fact employeeCategory = fact(
                EMPLOYEE_CATEGORY,
                "NET_REVENUE",
                "CHARGER_CABLE",
                Unit.MONEY,
                "15000.00",
                new Comparison(
                        new BigDecimal("10000.00"),
                        new BigDecimal("5000.00"),
                        new BigDecimal("50.00")
                )
        );
        Manifest manifest = new Manifest(
                List.of("E01"),
                List.of(
                        new EvidenceIndexEntry(
                                STORE_REVENUE, Scope.STORE, null, true
                        ),
                        new EvidenceIndexEntry(
                                EMPLOYEE_CATEGORY, Scope.EMPLOYEE, "E01", true
                        ),
                        new EvidenceIndexEntry(
                                UNAVAILABLE, Scope.STORE, null, false
                        ),
                        new EvidenceIndexEntry(
                                PLAN_STATUS, Scope.STORE, null, true
                        ),
                        new EvidenceIndexEntry(
                                EMPLOYEE_RANK, Scope.EMPLOYEE, "E01", true
                        )
                ),
                List.of(),
                List.of("CHARGER_CABLE"),
                Map.of(
                        "CHARGER_CABLE",
                        "Зарядные устройства и кабели"
                ),
                List.of(),
                List.of()
        );
        return new PersistedWeeklySnapshot(
                UUID.randomUUID(),
                storeId,
                new WeeklyAnalyticsFactsQuery(
                        storeId,
                        new StoreKpiPeriod(start, end),
                        new StoreKpiPeriod(
                                start.minusWeeks(1), end.minusWeeks(1)
                        )
                ),
                "Europe/Moscow",
                1,
                null,
                "INITIAL",
                null,
                UUID.randomUUID(),
                Instant.parse("2026-08-03T05:00:00Z"),
                Instant.parse("2026-08-03T05:00:00Z"),
                QualityStatus.PARTIAL,
                new Versions(1, "metrics-v3", "snapshot-v6", "quality-v3"),
                new WeeklySnapshotPayload(
                        1,
                        manifest,
                        new Facts(
                                List.of(storeRevenue, planStatus),
                                List.of(),
                                List.of(new EmployeeFacts(
                                        "E01",
                                        Sufficiency.SUFFICIENT,
                                        List.of("RESULT"),
                                        List.of(employeeCategory, employeeRank)
                                )),
                                List.of()
                        )
                ),
                "a".repeat(64),
                List.of(new SnapshotEmployeeMembership(
                        employeeId, "E01", "Ирина"
                )),
                Instant.parse("2026-08-03T05:00:01Z")
        );
    }

    private Fact fact(
            String reference,
            String metric,
            String category,
            Unit unit,
            String current,
            Comparison comparison
    ) {
        return new Fact(
                reference,
                metric,
                category,
                unit,
                new BigDecimal(current),
                comparison,
                Sufficiency.SUFFICIENT,
                Materiality.PRIMARY
        );
    }
}
