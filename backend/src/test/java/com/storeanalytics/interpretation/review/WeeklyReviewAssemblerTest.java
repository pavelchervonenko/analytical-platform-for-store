package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.interpretation.review.WeeklyReviewFacts.PeriodFacts;
import com.storeanalytics.interpretation.review.WeeklyReviewPolicyV1.RevenuePeriod;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.DateRange;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Effect;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricState;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.PeriodContext;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Provenance;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.ReportState;
import com.storeanalytics.interpretation.snapshot.EmployeeSalesSampleFacts;
import com.storeanalytics.metrics.service.AttachRateDataQuality;
import com.storeanalytics.metrics.service.AttachRateResult;
import com.storeanalytics.metrics.service.CategoryKpiDataQuality;
import com.storeanalytics.metrics.service.CategoryKpiEntry;
import com.storeanalytics.metrics.service.CategoryKpiGroup;
import com.storeanalytics.metrics.service.CategoryKpiMetrics;
import com.storeanalytics.metrics.service.CategoryKpiResult;
import com.storeanalytics.metrics.service.StoreKpiDataQuality;
import com.storeanalytics.metrics.service.StoreKpiResult;
import com.storeanalytics.performance.service.EmployeeRatingEntry;
import com.storeanalytics.performance.service.EmployeeRatingResult;
import com.storeanalytics.product.model.AnalyticsCategoryKind;
import com.storeanalytics.product.model.DeviceFamily;
import com.storeanalytics.store.service.StoreDataFreshnessStatus;
import com.storeanalytics.store.service.StoreDataStatusView;
import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

class WeeklyReviewAssemblerTest {

    private static final UUID STORE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ANNA_ID = UUID.fromString("d52840bc-99b9-4662-b724-7b5be5dfccbf");
    private static final UUID BORIS_ID = UUID.fromString("f5748bf4-d59b-443b-a4bd-e6507cdf3dbe");
    private static final UUID VERA_ID = UUID.fromString("b5fbc9bf-e425-4be3-bc4a-c7a7c2ae6f1b");
    private static final DateRange CURRENT = new DateRange(
            LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 23)
    );
    private static final DateRange PREVIOUS = new DateRange(
            LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 16)
    );
    private static final PeriodContext PERIOD = new PeriodContext(
            "Europe/Moscow",
            CURRENT,
            PREVIOUS,
            "17–23 августа 2026",
            "10–16 августа 2026"
    );

    private final WeeklyReviewAssembler assembler =
            new WeeklyReviewAssembler(new WeeklyReviewPolicyV1());

    @Test
    void producesCompleteDeterministicReportWithoutPlanOrAiDependency() {
        WeeklyReviewResponse result = assembler.assemble(
                facts(completeStatus(), quality(true, 0, 0, 0), quality(true, 0, 0, 0)),
                provenance()
        );

        assertThat(result.reportState()).isEqualTo(ReportState.READY);
        assertThat(result.summary().outcome().text())
                .contains("Чистая выручка за неделю", "Валовая прибыль")
                .doesNotContain("план", "Автоматическая интерпретация");
        assertThat(result.factors())
                .extracting(factor -> factor.comparison().code())
                .containsExactly("RETURN_REVENUE", "DEVICES_REVENUE");
        assertThat(result.factors())
                .extracting(WeeklyReviewResponse.Factor::title)
                .containsExactly(
                        "Возвраты выросли",
                        "Выручка направления «Техника» выросла"
                );
        assertThat(result.factors().getFirst().effect()).isEqualTo(Effect.NEGATIVE);
        assertThat(result.actions()).singleElement().satisfies(action -> {
            assertThat(action.metricCode()).isEqualTo("RETURN_REVENUE");
            assertThat(action.title()).isEqualTo("Разобрать рост возвратов");
            assertThat(action.target().operator()).isEqualTo("AT_MOST");
            assertThat(action.target().value()).isEqualByComparingTo("50.00");
        });
        assertThat(result.aiEnhancement().state())
                .isEqualTo(WeeklyReviewResponse.AiState.DISABLED);
        assertReferencesResolve(result);
    }

    @Test
    void serializedResponseMatchesFrontendGoldenContract() throws Exception {
        WeeklyReviewResponse result = assembler.assemble(
                facts(completeStatus(), quality(true, 0, 0, 0), quality(true, 0, 0, 0)),
                provenance()
        );
        ObjectMapper mapper = JsonMapper.builder()
                .findAndAddModules()
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        Path fixture = repositoryRoot().resolve(
                "frontend/src/test/fixtures/weekly-review-v2-ready.json"
        );
        JsonNode expected = mapper.readTree(Files.readString(fixture));
        JsonNode actual = mapper.readTree(mapper.writeValueAsString(result));

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void blocksReportOnlyWhenSalesOrReturnsDoNotCoverClosedWeek() {
        StoreDataStatusView incomplete = status(CURRENT.end(), PREVIOUS.end());

        WeeklyReviewResponse result = assembler.assemble(
                facts(incomplete, quality(true, 0, 0, 0), quality(true, 0, 0, 0)),
                provenance()
        );

        assertThat(result.reportState()).isEqualTo(ReportState.BLOCKED);
        assertThat(result.summary().state())
                .isEqualTo(WeeklyReviewResponse.BlockState.INSUFFICIENT);
        assertThat(result.factors()).isEmpty();
        assertThat(result.actions()).isEmpty();
    }

    @Test
    void missingCostDisablesOnlyProfitAndMargin() {
        WeeklyReviewResponse result = assembler.assemble(
                facts(
                        completeStatus(),
                        quality(false, 1, 0, 0),
                        quality(false, 1, 0, 0)
                ),
                provenance()
        );

        assertThat(metric(result, "NET_REVENUE").metricState()).isEqualTo(MetricState.READY);
        assertThat(metric(result, "AVERAGE_SALE").metricState()).isEqualTo(MetricState.READY);
        assertThat(metric(result, "GROSS_PROFIT").metricState())
                .isEqualTo(MetricState.UNAVAILABLE);
        assertThat(metric(result, "MARGIN_PERCENT").metricState())
                .isEqualTo(MetricState.UNAVAILABLE);
        assertThat(result.reportState()).isEqualTo(ReportState.PARTIAL);
    }

    @Test
    void sourceGraphHasNoMonthlyPlanOrRatingServiceDependency() {
        assertThat(List.of(WeeklyReviewFactsSource.class.getDeclaredFields()))
                .extracting(field -> field.getType().getSimpleName())
                .noneMatch(name -> name.contains("Plan") || name.equals("EmployeeRatingService"));
        assertThat(List.of(WeeklyReviewEmployeeFactsReader.class.getDeclaredFields()))
                .extracting(field -> field.getType().getSimpleName())
                .noneMatch(name -> name.contains("Plan") || name.equals("EmployeeRatingService"));
    }

    private void assertReferencesResolve(WeeklyReviewResponse response) {
        Set<String> available = response.evidence().stream()
                .map(WeeklyReviewResponse.Evidence::evidenceRef)
                .collect(Collectors.toSet());
        assertThat(response.factors())
                .allSatisfy(factor -> assertThat(available).containsAll(factor.evidenceRefs()));
        assertThat(response.actions())
                .allSatisfy(action -> assertThat(available).containsAll(action.evidenceRefs()));
    }

    private WeeklyReviewResponse.MetricComparison metric(
            WeeklyReviewResponse response,
            String code
    ) {
        return response.results().stream()
                .filter(metric -> code.equals(metric.code()))
                .findFirst()
                .orElseThrow();
    }

    private WeeklyReviewFacts facts(
            StoreDataStatusView status,
            StoreKpiDataQuality currentQuality,
            StoreKpiDataQuality previousQuality
    ) {
        EmployeeRatingEntry annaCurrent = employee("Анна", "400.00");
        EmployeeRatingEntry borisCurrent = employee("Борис", "350.00");
        EmployeeRatingEntry veraCurrent = employee("Вера", "250.00");
        EmployeeRatingEntry annaPrevious = copy(annaCurrent, "360.00");
        EmployeeRatingEntry borisPrevious = copy(borisCurrent, "320.00");
        EmployeeRatingEntry veraPrevious = copy(veraCurrent, "220.00");
        return new WeeklyReviewFacts(
                STORE_ID,
                PERIOD,
                status,
                periodFacts(
                        store("1000.00", "600.00", currentQuality),
                        categories("700.00", "500.00", "100.00", "100.00"),
                        ratings(annaCurrent, borisCurrent, veraCurrent),
                        samples(annaCurrent, borisCurrent, veraCurrent),
                        revenue("1100.00", "100.00", 18, 2)
                ),
                periodFacts(
                        store("900.00", "540.00", previousQuality),
                        categories("600.00", "450.00", "90.00", "90.00"),
                        ratings(annaPrevious, borisPrevious, veraPrevious),
                        samples(annaPrevious, borisPrevious, veraPrevious),
                        revenue("950.00", "50.00", 18, 1)
                ),
                Instant.parse("2026-08-24T03:50:00Z")
        );
    }

    private PeriodFacts periodFacts(
            StoreKpiResult store,
            CategoryKpiResult categories,
            EmployeeRatingResult employees,
            EmployeeSalesSampleFacts samples,
            RevenuePeriod revenue
    ) {
        return new PeriodFacts(
                store,
                categories,
                new AttachRateResult(
                        STORE_ID,
                        CURRENT.start(),
                        CURRENT.end(),
                        "attach-rate-v3",
                        new AttachRateDataQuality(0, 0, 0),
                        List.of()
                ),
                employees,
                samples,
                0,
                revenue
        );
    }

    private StoreKpiResult store(
            String revenue,
            String cost,
            StoreKpiDataQuality quality
    ) {
        BigDecimal netRevenue = new BigDecimal(revenue);
        BigDecimal costAmount = new BigDecimal(cost);
        BigDecimal grossProfit = netRevenue.subtract(costAmount);
        BigDecimal margin = netRevenue.signum() <= 0
                ? null
                : grossProfit.multiply(BigDecimal.valueOf(100))
                        .divide(netRevenue, 2, java.math.RoundingMode.HALF_UP);
        return new StoreKpiResult(
                STORE_ID,
                CURRENT.start(),
                CURRENT.end(),
                "store-kpi-v3",
                netRevenue,
                BigDecimal.TEN,
                quality.completeCostData() ? costAmount : null,
                quality.completeCostData() ? grossProfit : null,
                quality.completeCostData() ? margin : null,
                quality
        );
    }

    private StoreKpiDataQuality quality(
            boolean completeCost,
            long missingCost,
            long unmapped,
            long consistency
    ) {
        return new StoreKpiDataQuality(
                completeCost,
                10,
                unmapped,
                missingCost,
                0,
                consistency,
                consistency
        );
    }

    private CategoryKpiResult categories(
            String devices,
            String phones,
            String accessory,
            String service
    ) {
        BigDecimal additional = new BigDecimal(accessory).add(new BigDecimal(service));
        List<CategoryKpiEntry> entries = List.of(
                category("PHONE", AnalyticsCategoryKind.DEVICE, true, true, false, phones),
                category(
                        "OTHER_DEVICE",
                        AnalyticsCategoryKind.DEVICE,
                        false,
                        true,
                        false,
                        new BigDecimal(devices).subtract(new BigDecimal(phones)).toPlainString()
                ),
                category("CASE", AnalyticsCategoryKind.ACCESSORY,
                        false, false, true, accessory),
                category("SERVICE", AnalyticsCategoryKind.SERVICE,
                        false, false, true, service)
        );
        return new CategoryKpiResult(
                STORE_ID,
                CURRENT.start(),
                CURRENT.end(),
                "category-kpi-v3",
                List.of(
                        group("PHONES", phones),
                        group("DEVICES", devices),
                        group("ACCESSORY", accessory),
                        group("SERVICE", service),
                        group("ADDITIONAL_REVENUE", additional.toPlainString())
                ),
                entries
        );
    }

    private CategoryKpiGroup group(String code, String revenue) {
        return new CategoryKpiGroup(code, code, categoryMetrics(revenue));
    }

    private CategoryKpiEntry category(
            String code,
            AnalyticsCategoryKind kind,
            boolean phone,
            boolean device,
            boolean additional,
            String revenue
    ) {
        return new CategoryKpiEntry(
                code,
                code,
                kind,
                DeviceFamily.NONE,
                true,
                phone,
                device,
                additional,
                categoryMetrics(revenue)
        );
    }

    private CategoryKpiMetrics categoryMetrics(String revenue) {
        BigDecimal value = new BigDecimal(revenue);
        return new CategoryKpiMetrics(
                value,
                BigDecimal.ONE,
                BigDecimal.ZERO.setScale(2),
                value,
                value,
                new BigDecimal("100.00"),
                new CategoryKpiDataQuality(true, 1, 0, 0)
        );
    }

    private RevenuePeriod revenue(String sales, String returns, long salesCount, long returnCount) {
        BigDecimal saleRevenue = new BigDecimal(sales);
        BigDecimal returnRevenue = new BigDecimal(returns);
        return new RevenuePeriod(
                saleRevenue,
                returnRevenue,
                saleRevenue.subtract(returnRevenue),
                salesCount,
                returnCount
        );
    }

    private EmployeeRatingResult ratings(EmployeeRatingEntry... employees) {
        return new EmployeeRatingResult(
                STORE_ID,
                CURRENT.start(),
                CURRENT.end(),
                null,
                null,
                List.of(employees),
                null
        );
    }

    private EmployeeSalesSampleFacts samples(EmployeeRatingEntry... employees) {
        return new EmployeeSalesSampleFacts(Map.of(
                employees[0].employeeId(), 6L,
                employees[1].employeeId(), 6L,
                employees[2].employeeId(), 6L
        ));
    }

    private EmployeeRatingEntry employee(String name, String revenue) {
        BigDecimal value = new BigDecimal(revenue);
        return new EmployeeRatingEntry(
                employeeId(name),
                name,
                true,
                true,
                true,
                true,
                2,
                new BigDecimal("14.00"),
                value,
                null,
                value.divide(BigDecimal.valueOf(2)),
                value.divide(BigDecimal.valueOf(14), 2, java.math.RoundingMode.HALF_UP),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                false,
                null,
                List.of()
        );
    }

    private UUID employeeId(String name) {
        return switch (name) {
            case "Анна" -> ANNA_ID;
            case "Борис" -> BORIS_ID;
            case "Вера" -> VERA_ID;
            default -> throw new IllegalArgumentException("Unknown fixture employee: " + name);
        };
    }

    private EmployeeRatingEntry copy(EmployeeRatingEntry source, String revenue) {
        BigDecimal value = new BigDecimal(revenue);
        return new EmployeeRatingEntry(
                source.employeeId(),
                source.displayName(),
                true,
                true,
                true,
                true,
                2,
                new BigDecimal("14.00"),
                value,
                null,
                value.divide(BigDecimal.valueOf(2)),
                value.divide(BigDecimal.valueOf(14), 2, java.math.RoundingMode.HALF_UP),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                false,
                null,
                List.of()
        );
    }

    private StoreDataStatusView completeStatus() {
        return status(CURRENT.end(), CURRENT.end());
    }

    private StoreDataStatusView status(LocalDate salesThrough, LocalDate returnsThrough) {
        return new StoreDataStatusView(
                STORE_ID,
                StoreDataFreshnessStatus.CURRENT,
                CURRENT.end(),
                CURRENT.end(),
                salesThrough,
                returnsThrough,
                0,
                Instant.parse("2026-08-24T03:50:00Z"),
                null,
                0,
                null,
                null,
                Instant.parse("2026-08-26T12:00:00Z")
        );
    }

    private Provenance provenance() {
        return new Provenance(
                "weekly-review-test",
                1,
                Instant.parse("2026-08-24T04:00:00Z"),
                Instant.parse("2026-08-24T03:50:00Z"),
                false,
                null
        );
    }

    private Path repositoryRoot() {
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        if (Files.isDirectory(workingDirectory.resolve("backend/src/main/java"))) {
            return workingDirectory;
        }
        Path parent = workingDirectory.getParent();
        if (parent != null && Files.isDirectory(parent.resolve("backend/src/main/java"))) {
            return parent;
        }
        throw new IllegalStateException("Cannot locate repository root");
    }
}
