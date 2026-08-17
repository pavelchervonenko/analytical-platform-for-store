package com.storeanalytics.interpretation.generation;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Comparison;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.EmployeeFacts;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.EvidenceIndexEntry;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Fact;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Facts;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Limitation;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.LimitationImpact;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Manifest;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Materiality;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Period;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.QualityStatus;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Scope;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Snapshot;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Sufficiency;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Unit;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Versions;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LlmProviderInputCompactorTest {

    private final LlmProviderInputCompactor compactor = new LlmProviderInputCompactor();

    @Test
    void keepsTopStoreCategoriesAndOnlyWorkloadStatusForInsufficientEmployee() {
        Fact categoryA = category("A", "100");
        Fact categoryB = category("B", "200");
        Fact categoryC = category("C", "50");
        Fact categoryD = category("D", "300");
        Fact workload = fact(
                "EMPLOYEE:E01.WORKLOAD_STATUS",
                "WORKLOAD_STATUS",
                null,
                Unit.STATUS,
                "NO_SHIFTS",
                Materiality.CONTEXT
        );
        Fact employeeRevenue = fact(
                "EMPLOYEE:E01.NET_REVENUE",
                "NET_REVENUE",
                null,
                Unit.MONEY,
                new BigDecimal("120000"),
                Materiality.PRIMARY
        );
        WeeklyInterpretationInput source = input(
                List.of(categoryA, categoryB, categoryC, categoryD),
                new EmployeeFacts(
                        "E01",
                        Sufficiency.INSUFFICIENT,
                        List.of("workload"),
                        List.of(workload, employeeRevenue)
                )
        );

        WeeklyInterpretationInput result = compactor.compact(source);

        assertThat(result.facts().store())
                .extracting(Fact::categoryCode)
                .containsExactly("B", "D");
        assertThat(result.facts().employees().getFirst().facts())
                .extracting(Fact::metricCode)
                .containsExactly("WORKLOAD_STATUS");
        assertThat(result.manifest().categoryCodes()).containsExactly("B", "D");
        assertThat(result.manifest().categoryLabels())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "B", "Категория B",
                        "D", "Категория D"
                ));
        assertThat(result.manifest().evidence())
                .extracting(EvidenceIndexEntry::evidenceRef)
                .containsExactly(
                        categoryB.evidenceRef(),
                        categoryD.evidenceRef(),
                        workload.evidenceRef()
                );
    }

    @Test
    void keepsReceiptNumeratorDenominatorAndRateForSelectedAttachMetric() {
        List<Fact> attachFacts = List.of(
                attach("CASE_APPLE_IPHONE", "NUMERATOR_QUANTITY", "2"),
                attach("CASE_APPLE_IPHONE", "DENOMINATOR_QUANTITY", "5"),
                attach("CASE_APPLE_IPHONE", "RATE_PER_HUNDRED", "40"),
                attach("CHARGER_CABLE", "NUMERATOR_QUANTITY", "7"),
                attach("CHARGER_CABLE", "DENOMINATOR_QUANTITY", "10"),
                attach("CHARGER_CABLE", "RATE_PER_HUNDRED", "70")
        );
        Fact workload = fact(
                "EMPLOYEE:E01.WORKLOAD_STATUS",
                "WORKLOAD_STATUS",
                null,
                Unit.STATUS,
                "SUFFICIENT",
                Materiality.CONTEXT
        );
        WeeklyInterpretationInput source = input(
                attachFacts,
                new EmployeeFacts(
                        "E01",
                        Sufficiency.INSUFFICIENT,
                        List.of("workload"),
                        List.of(workload)
                )
        );

        WeeklyInterpretationInput result = compactor.compact(source);

        assertThat(result.facts().store())
                .extracting(Fact::evidenceRef)
                .containsExactly(
                        "STORE.ATTACH:CHARGER_CABLE.NUMERATOR_QUANTITY",
                        "STORE.ATTACH:CHARGER_CABLE.DENOMINATOR_QUANTITY",
                        "STORE.ATTACH:CHARGER_CABLE.RATE_PER_HUNDRED"
                );
    }

    @Test
    void preservesBackendLimitationAndItsOtherwiseCompactedEvidence() {
        Fact categoryA = category("A", "100");
        Fact categoryB = category("B", "200");
        Fact categoryC = category("C", "50");
        Fact categoryD = category("D", "300");
        Fact workload = fact(
                "EMPLOYEE:E01.WORKLOAD_STATUS",
                "WORKLOAD_STATUS",
                null,
                Unit.STATUS,
                "NO_SHIFTS",
                Materiality.CONTEXT
        );
        WeeklyInterpretationInput base = input(
                List.of(categoryA, categoryB, categoryC, categoryD),
                new EmployeeFacts(
                        "E01",
                        Sufficiency.INSUFFICIENT,
                        List.of("workload"),
                        List.of(workload)
                )
        );
        Manifest manifest = base.manifest();
        Limitation limitation = new Limitation(
                "CATEGORY_DATA_LIMITED",
                Scope.CATEGORY,
                null,
                "C",
                LimitationImpact.REDUCED_CONFIDENCE,
                List.of("CATEGORY_PERFORMANCE"),
                List.of(categoryC.evidenceRef())
        );
        WeeklyInterpretationInput source = new WeeklyInterpretationInput(
                base.contractVersion(),
                base.snapshot(),
                new Manifest(
                        manifest.employeeRefs(),
                        manifest.evidence(),
                        manifest.candidateRefs(),
                        manifest.categoryCodes(),
                        manifest.categoryLabels(),
                        manifest.competencyCodes(),
                        List.of(limitation)
                ),
                base.facts()
        );

        WeeklyInterpretationInput result = compactor.compact(source);

        assertThat(result.facts().store())
                .extracting(Fact::categoryCode)
                .containsExactly("B", "C", "D");
        assertThat(result.manifest().evidence())
                .extracting(EvidenceIndexEntry::evidenceRef)
                .contains(categoryC.evidenceRef());
        assertThat(result.manifest().limitations()).containsExactly(limitation);
        assertThat(result.manifest().categoryLabels()).containsKey("C");
    }

    @Test
    void keepsWorkloadFactsForAnalyzableEmployee() {
        Fact shifts = fact(
                "EMPLOYEE:E01.SHIFT_COUNT",
                "SHIFT_COUNT",
                null,
                Unit.COUNT,
                new BigDecimal("3"),
                Materiality.CONTEXT
        );
        Fact hours = fact(
                "EMPLOYEE:E01.WORKED_HOURS",
                "WORKED_HOURS",
                null,
                Unit.HOURS,
                new BigDecimal("24"),
                Materiality.CONTEXT
        );
        Fact status = fact(
                "EMPLOYEE:E01.WORKLOAD_STATUS",
                "WORKLOAD_STATUS",
                null,
                Unit.STATUS,
                "SUFFICIENT",
                Materiality.CONTEXT
        );
        WeeklyInterpretationInput source = input(
                List.of(),
                new EmployeeFacts(
                        "E01",
                        Sufficiency.SUFFICIENT,
                        List.of("WORKLOAD"),
                        List.of(shifts, hours, status)
                )
        );

        assertThat(compactor.compact(source)
                .facts().employees().getFirst().facts())
                .extracting(Fact::metricCode)
                .containsExactly("SHIFT_COUNT", "WORKED_HOURS", "WORKLOAD_STATUS");
    }

    private WeeklyInterpretationInput input(
            List<Fact> storeFacts,
            EmployeeFacts employee
    ) {
        Snapshot snapshot = new Snapshot(
                UUID.fromString("00000000-0000-4000-8000-000000000001"),
                1,
                "a".repeat(64),
                "S01",
                "Europe/Moscow",
                new Period(LocalDate.of(2026, 7, 27), LocalDate.of(2026, 8, 2)),
                new Period(LocalDate.of(2026, 7, 20), LocalDate.of(2026, 7, 26)),
                QualityStatus.READY,
                new Versions(1, "metrics-v1", "weekly-snapshot-v2", "quality-v1")
        );
        List<EvidenceIndexEntry> evidence = new ArrayList<>();
        storeFacts.forEach(fact -> evidence.add(new EvidenceIndexEntry(
                fact.evidenceRef(),
                fact.evidenceRef().contains(".CATEGORY:")
                        ? Scope.CATEGORY : Scope.METRIC,
                null,
                true
        )));
        employee.facts().forEach(fact -> evidence.add(new EvidenceIndexEntry(
                fact.evidenceRef(),
                Scope.EMPLOYEE,
                employee.employeeRef(),
                true
        )));
        evidence.add(new EvidenceIndexEntry(
                "STORE.UNAVAILABLE", Scope.STORE, null, false
        ));
        Manifest manifest = new Manifest(
                List.of("E01"),
                evidence,
                List.of(),
                List.of("A", "B", "C", "D"),
                Map.of(
                        "A", "Категория A",
                        "B", "Категория B",
                        "C", "Категория C",
                        "D", "Категория D"
                ),
                List.of(),
                List.of()
        );
        return new WeeklyInterpretationInput(
                1,
                snapshot,
                manifest,
                new Facts(storeFacts, List.of(), List.of(employee), List.of())
        );
    }

    private Fact category(String category, String value) {
        return fact(
                "STORE.CATEGORY:" + category + ".NET_REVENUE",
                "NET_REVENUE",
                category,
                Unit.MONEY,
                new BigDecimal(value),
                Materiality.SECONDARY
        );
    }

    private Fact attach(String metric, String factCode, String value) {
        return fact(
                "STORE.ATTACH:" + metric + "." + factCode,
                factCode,
                metric,
                Unit.COUNT,
                new BigDecimal(value),
                Materiality.SECONDARY
        );
    }

    private Fact fact(
            String evidenceRef,
            String metricCode,
            String categoryCode,
            Unit unit,
            Object value,
            Materiality materiality
    ) {
        return new Fact(
                evidenceRef,
                metricCode,
                categoryCode,
                unit,
                value,
                new Comparison(null, null, null),
                Sufficiency.SUFFICIENT,
                materiality
        );
    }
}
