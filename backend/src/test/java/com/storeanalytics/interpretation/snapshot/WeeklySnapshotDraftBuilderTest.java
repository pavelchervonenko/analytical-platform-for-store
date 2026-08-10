package com.storeanalytics.interpretation.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.interpretation.contract.LlmContractResources;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.QualityStatus;
import com.storeanalytics.interpretation.validation.LlmJsonSchemaValidator;
import com.storeanalytics.metrics.service.AttachRateDataQuality;
import com.storeanalytics.metrics.service.AttachRateResult;
import com.storeanalytics.metrics.service.AverageKpiResult;
import com.storeanalytics.metrics.service.CategoryKpiResult;
import com.storeanalytics.metrics.service.EmployeeCategoryKpiResult;
import com.storeanalytics.metrics.service.EmployeeKpiDataQuality;
import com.storeanalytics.metrics.service.EmployeeKpiEntry;
import com.storeanalytics.metrics.service.EmployeeKpiResult;
import com.storeanalytics.metrics.service.StoreKpiDataQuality;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import com.storeanalytics.metrics.service.StoreKpiResult;
import com.storeanalytics.performance.service.EmployeeRatingEntry;
import com.storeanalytics.performance.service.EmployeeRatingResult;
import com.storeanalytics.performance.service.RatingScoreBreakdown;
import com.storeanalytics.store.service.StoreDataFreshnessStatus;
import com.storeanalytics.store.service.StoreDataStatusView;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class WeeklySnapshotDraftBuilderTest {

    private static final UUID STORE_ID = UUID.fromString(
            "00000000-0000-4000-8000-000000000100"
    );
    private static final UUID EMPLOYEE_ID = UUID.fromString(
            "00000000-0000-4000-8000-000000000200"
    );
    private static final UUID OPTED_OUT_EMPLOYEE_ID = UUID.fromString(
            "00000000-0000-4000-8000-000000000201"
    );
    private static final String PRIVATE_NAME = "Private Manager Name";

    private final WeeklySnapshotPayloadCodec codec = new WeeklySnapshotPayloadCodec();
    private final WeeklySnapshotDraftBuilder builder = new WeeklySnapshotDraftBuilder(codec);
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void producesSchemaValidPseudonymizedInputAndStableHash() throws Exception {
        WeeklyAnalyticsFacts source = source(StoreDataFreshnessStatus.CURRENT, false);

        WeeklySnapshotDraft first = builder.build(source, "Europe/Moscow");
        WeeklySnapshotDraft second = builder.build(source, "Europe/Moscow");
        WeeklyInterpretationInput input = new WeeklyInterpretationInputAssembler().assemble(
                first,
                UUID.fromString("00000000-0000-4000-8000-000000000300"),
                1,
                "S01"
        );
        String json = objectMapper.writeValueAsString(input);

        assertThat(first.qualityStatus()).isEqualTo(QualityStatus.READY);
        assertThat(first.factsHash()).isEqualTo(second.factsHash()).hasSize(64);
        assertThat(first.versions().calculationVersion()).isEqualTo("weekly-snapshot-v5");
        assertThat(first.versions().qualityPolicyVersion()).isEqualTo("weekly-quality-v2");
        assertThat(first.payload().manifest().competencyCodes()).containsExactly(
                "ACCESSORY_SALES",
                "ADDITIONAL_SALES",
                "ATTACH_RATE",
                "COMMERCIAL_CONTRIBUTION",
                "TIME_EFFICIENCY"
        );
        assertThat(first.payload().facts().employees()).singleElement().satisfies(employee ->
                assertThat(employee.facts())
                        .filteredOn(fact -> "COMPLETED_SALES".equals(fact.metricCode()))
                        .singleElement()
                        .satisfies(fact -> {
                            assertThat(fact.value().toString()).isEqualTo("8");
                            assertThat(fact.sufficiency()).isEqualTo(WeeklyInterpretationInput.Sufficiency.SUFFICIENT);
                        }));
        assertThat(first.employees()).singleElement().satisfies(member -> {
            assertThat(member.employeeId()).isEqualTo(EMPLOYEE_ID);
            assertThat(member.employeeRef()).isEqualTo("E01");
            assertThat(member.displayNameSnapshot()).isEqualTo(PRIVATE_NAME);
        });
        assertThat(json).doesNotContain(PRIVATE_NAME, EMPLOYEE_ID.toString(), STORE_ID.toString());
        assertThat(new LlmJsonSchemaValidator(LlmContractResources.INPUT_SCHEMA).validate(json))
                .isEmpty();
    }

    @Test
    void recordsBackendOwnedLimitationWhenEmployeeAnalysisIsInsufficient() {
        WeeklyAnalyticsFacts source = source(StoreDataFreshnessStatus.CURRENT, false);
        WeeklyPeriodFacts current = source.current();
        WeeklyPeriodFacts currentWithoutEmployeeKpi = new WeeklyPeriodFacts(
                current.store(),
                current.categories(),
                current.attachRates(),
                new EmployeeKpiResult(
                        STORE_ID,
                        source.query().period().start(),
                        source.query().period().end(),
                        "employee-kpi-v1",
                        List.of()
                ),
                current.employeeCategories(),
                current.employeeRatings(),
                current.employeeSalesSamples()
        );
        WeeklyAnalyticsFacts withEmployeeQualityGap = new WeeklyAnalyticsFacts(
                source.storeId(),
                source.query(),
                source.sourceDataStatus(),
                currentWithoutEmployeeKpi,
                source.previous(),
                source.averageComparisons(),
                source.planContexts()
        );

        WeeklySnapshotDraft draft = builder.build(
                withEmployeeQualityGap,
                "Europe/Moscow"
        );

        assertThat(draft.payload().manifest().limitations())
                .filteredOn(limitation -> limitation.employeeRef() != null)
                .singleElement()
                .satisfies(limitation -> {
                    assertThat(limitation.code())
                            .isEqualTo("EMPLOYEE_ANALYSIS_INSUFFICIENT");
                    assertThat(limitation.employeeRef()).isEqualTo("E01");
                    assertThat(limitation.impact())
                            .isEqualTo(WeeklyInterpretationInput.LimitationImpact.UNAVAILABLE);
                    assertThat(limitation.affectedSections())
                            .contains("RESULT", "RATING", "TEAM_COMPARISON");
                    assertThat(limitation.evidenceRefs())
                            .contains("EMP:E01.WORKLOAD.STATUS");
                });
    }

    @Test
    void blocksProviderInputWhenSourceDoesNotCoverTheCompletedWeek() {
        WeeklySnapshotDraft draft = builder.build(
                source(StoreDataFreshnessStatus.STALE, true),
                "Europe/Moscow"
        );

        assertThat(draft.qualityStatus()).isEqualTo(QualityStatus.BLOCKED);
        assertThat(draft.payload().manifest().limitations())
                .extracting(WeeklyInterpretationInput.Limitation::code)
                .containsExactly("SOURCE_DATA_INCOMPLETE");
        assertThatThrownBy(() -> new WeeklyInterpretationInputAssembler().assemble(
                draft,
                UUID.randomUUID(),
                1,
                "S01"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BLOCKED snapshots");
    }

    private WeeklyAnalyticsFacts source(
            StoreDataFreshnessStatus freshness,
            boolean incomplete
    ) {
        StoreKpiPeriod currentPeriod = new StoreKpiPeriod(
                LocalDate.of(2026, 7, 20),
                LocalDate.of(2026, 7, 26)
        );
        StoreKpiPeriod previousPeriod = new StoreKpiPeriod(
                LocalDate.of(2026, 7, 13),
                LocalDate.of(2026, 7, 19)
        );
        WeeklyAnalyticsFactsQuery query = new WeeklyAnalyticsFactsQuery(
                STORE_ID,
                currentPeriod,
                previousPeriod
        );
        LocalDate dataThrough = incomplete
                ? currentPeriod.end().minusDays(1)
                : currentPeriod.end();
        StoreDataStatusView status = new StoreDataStatusView(
                STORE_ID,
                freshness,
                currentPeriod.end(),
                dataThrough,
                dataThrough,
                dataThrough,
                incomplete ? 1 : 0,
                Instant.parse("2026-07-27T03:00:00Z"),
                null,
                0,
                null,
                null,
                Instant.parse("2026-07-27T04:00:00Z")
        );
        return new WeeklyAnalyticsFacts(
                STORE_ID,
                query,
                status,
                period(currentPeriod, "100000.00", "12"),
                period(previousPeriod, "90000.00", "10"),
                new AverageKpiResult(
                        STORE_ID,
                        currentPeriod.start(),
                        currentPeriod.end(),
                        previousPeriod.start(),
                        previousPeriod.end(),
                        "average-kpi-v1",
                        null,
                        null,
                        List.of()
                ),
                List.of()
        );
    }

    private WeeklyPeriodFacts period(
            StoreKpiPeriod period,
            String revenue,
            String quantity
    ) {
        BigDecimal netRevenue = new BigDecimal(revenue);
        BigDecimal netQuantity = new BigDecimal(quantity);
        StoreKpiDataQuality quality = new StoreKpiDataQuality(true, 12, 0, 0, 0, 0, 0);
        StoreKpiResult store = new StoreKpiResult(
                STORE_ID,
                period.start(),
                period.end(),
                "store-kpi-v1",
                netRevenue,
                netQuantity,
                new BigDecimal("60000.00"),
                new BigDecimal("40000.00"),
                new BigDecimal("40.00"),
                quality
        );
        EmployeeKpiEntry employee = new EmployeeKpiEntry(
                EMPLOYEE_ID,
                PRIVATE_NAME,
                true,
                true,
                true,
                true,
                true,
                false,
                netRevenue,
                netQuantity,
                new BigDecimal("60000.00"),
                new BigDecimal("40000.00"),
                new BigDecimal("40.00"),
                new EmployeeKpiDataQuality(true, 12, 0, 0, 0)
        );
        EmployeeKpiEntry optedOutEmployee = new EmployeeKpiEntry(
                OPTED_OUT_EMPLOYEE_ID,
                "Excluded from analysis",
                true,
                true,
                true,
                false,
                false,
                false,
                new BigDecimal("5000.00"),
                new BigDecimal("1.00"),
                new BigDecimal("3000.00"),
                new BigDecimal("2000.00"),
                new BigDecimal("40.00"),
                new EmployeeKpiDataQuality(true, 1, 0, 0, 0)
        );
        EmployeeRatingEntry rating = rating(netRevenue);
        return new WeeklyPeriodFacts(
                store,
                new CategoryKpiResult(
                        STORE_ID, period.start(), period.end(), "category-kpi-v1",
                        List.of(), List.of()
                ),
                new AttachRateResult(
                        STORE_ID, period.start(), period.end(), "attach-rate-v2",
                        new AttachRateDataQuality(0, 0, 0), List.of()
                ),
                new EmployeeKpiResult(
                        STORE_ID, period.start(), period.end(), "employee-kpi-v1",
                        List.of(employee, optedOutEmployee)
                ),
                new EmployeeCategoryKpiResult(
                        STORE_ID, period.start(), period.end(), "employee-kpi-v1",
                        "employee-category-kpi-v1", List.of()
                ),
                new EmployeeRatingResult(
                        STORE_ID, period.start(), period.end(), null, null,
                        List.of(rating), null
                ),
                new EmployeeSalesSampleFacts(Map.of(EMPLOYEE_ID, 8L))
        );
    }

    private EmployeeRatingEntry rating(BigDecimal revenue) {
        RatingScoreBreakdown scores = new RatingScoreBreakdown(
                new BigDecimal("80.00"),
                new BigDecimal("24.00"),
                new BigDecimal("82.00"),
                new BigDecimal("24.60"),
                new BigDecimal("78.00"),
                new BigDecimal("15.60"),
                new BigDecimal("75.00"),
                new BigDecimal("15.00"),
                new BigDecimal("100.00"),
                new BigDecimal("79.20")
        );
        return new EmployeeRatingEntry(
                EMPLOYEE_ID,
                PRIVATE_NAME,
                true,
                true,
                true,
                true,
                2,
                new BigDecimal("16.00"),
                revenue,
                new BigDecimal("100.00"),
                revenue.divide(BigDecimal.valueOf(2)),
                revenue.divide(BigDecimal.valueOf(16)),
                new BigDecimal("10000.00"),
                new BigDecimal("10.00"),
                new BigDecimal("5000.00"),
                new BigDecimal("5.00"),
                new BigDecimal("15000.00"),
                new BigDecimal("15.00"),
                scores,
                true,
                1,
                List.of()
        );
    }
}
