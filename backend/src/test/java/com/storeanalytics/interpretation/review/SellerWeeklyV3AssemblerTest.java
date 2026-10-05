package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse.DateRange;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricState;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.PeriodContext;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Provenance;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.ReportState;
import com.storeanalytics.metrics.repository.EmployeeCategoryKpiAggregate;
import com.storeanalytics.metrics.repository.EmployeeKpiAggregate;
import com.storeanalytics.metrics.repository.SellerDocumentAggregate;
import com.storeanalytics.metrics.service.AttachRateDataQuality;
import com.storeanalytics.metrics.service.AttachRateResult;
import com.storeanalytics.metrics.service.CategoryKpiDataQuality;
import com.storeanalytics.metrics.service.CategoryKpiGroup;
import com.storeanalytics.metrics.service.CategoryKpiMetrics;
import com.storeanalytics.metrics.service.CategoryKpiResult;
import com.storeanalytics.metrics.service.SellerCohortSnapshot;
import com.storeanalytics.metrics.service.SellerHistoricalComparisonFacts;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import com.storeanalytics.metrics.service.SellerPeriodComparisonFacts;
import com.storeanalytics.metrics.service.SellerPeriodFacts;
import com.storeanalytics.metrics.service.SellerPeriodMetrics;
import com.storeanalytics.metrics.service.SellerReturnAttributionQuality;
import com.storeanalytics.product.model.AnalyticsCategoryKind;
import com.storeanalytics.product.model.DeviceFamily;
import com.storeanalytics.store.service.StoreDataStatusView;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public class SellerWeeklyV3AssemblerTest {

    private static final DateRange CURRENT = new DateRange(
            LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 23));
    private static final DateRange PREVIOUS = new DateRange(
            LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 16));
    private static final UUID STORE = UUID.randomUUID();
    private static final UUID SELLER = UUID.randomUUID();
    private static final SellerCohortSnapshot COHORT = new SellerCohortSnapshot(STORE, List.of(SELLER));
    private static final String SOURCE_HASH = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-08-24T08:00:00Z");

    public static WeeklyReviewV3Response syntheticResponse() {
        var fixture = new SellerWeeklyV3AssemblerTest();
        return new SellerWeeklyV3Assembler().assemble(fixture.source(CURRENT.end()),
                fixture.provenance(), SOURCE_HASH, NOW);
    }

    @Test
    void exportsSyntheticContractOnlyWhenArtifactPathIsRequested() throws java.io.IOException {
        String payload = new WeeklyReviewV3SnapshotCodec().serialize(syntheticResponse());
        assertThat(payload).contains("CURRENT_RANKING_AT_GENERATION");
        String output = System.getProperty("seller.contract.output", "").trim();
        if (!output.isEmpty()) {
            java.nio.file.Path path = java.nio.file.Path.of(output).toAbsolutePath().normalize();
            java.nio.file.Files.createDirectories(path.getParent());
            java.nio.file.Files.writeString(path, payload);
        }
    }

    @Test
    void completeSellerFactsProduceReadyV3WithoutStoreLeakageOrShiftAssessment() {
        var response = new SellerWeeklyV3Assembler().assemble(
                source(CURRENT.end()), provenance(), SOURCE_HASH, NOW);

        assertThat(response.scope()).isEqualTo("SELLERS");
        assertThat(response.sourceCoverage()).anyMatch(item -> "ORDERS".equals(item.sourceCode())
                && item.requiredForReport() && item.state() == WeeklyReviewResponse.CoverageState.COMPLETE);
        assertThat(response.reportState()).isEqualTo(ReportState.READY);
        assertThat(response.results().getFirst().current()).isEqualByComparingTo("100");
        assertThat(response.results().getFirst().evidenceRefs()).containsExactly("SELLERS.NET_REVENUE");
        assertThat(response.additionalSales().revenue().current()).isEqualByComparingTo("20");
        assertThat(response.teamDisplay().totalCount()).isEqualTo(1);
        assertThat(response.teamDisplay().displayedCount()).isEqualTo(1);
        assertThat(response.employees()).hasSize(1);
        assertThat(response.employees().getFirst().card().metrics().shiftCount().current()).isNull();
        assertThat(response.employees().getFirst().card().peerComparison()).isNull();
        assertThat(response.employees().getFirst().card().action()).isNull();
        assertThat(response.limitations()).noneMatch(item ->
                "SHIFT_COVERAGE_UNVERIFIED".equals(item.code()));
        assertThat(response.team().state()).isEqualTo(WeeklyReviewResponse.BlockState.READY);
        assertThat(response.evidence()).allMatch(item -> !"STORE".equals(item.scope()));
        var codec = new WeeklyReviewV3SnapshotCodec();
        String payload = codec.serialize(response);
        assertThat(codec.serialize(codec.deserialize(payload))).isEqualTo(payload);
        assertThat(codec.contentHash(codec.deserialize(payload))).isEqualTo(codec.contentHash(response));
        assertThat(codec.contentHash(response)).hasSize(64);
    }

    @Test
    void missingReturnEmployeeKeepsKnownTotalsButAddsScopedPartialLimitation() {
        SellerWeeklyReviewFacts facts = source(CURRENT.end());
        when(facts.comparison().current().returnAttribution())
                .thenReturn(new SellerReturnAttributionQuality(1, 0));

        var response = new SellerWeeklyV3Assembler().assemble(facts, provenance(), SOURCE_HASH, NOW);

        assertThat(response.reportState()).isEqualTo(ReportState.PARTIAL);
        assertThat(response.summary().outcome().effect())
                .isEqualTo(WeeklyReviewResponse.Effect.NEUTRAL);
        assertThat(response.summary().outcome().text()).contains("итог недели предварителен");
        assertThat(response.results().getFirst().current()).isEqualByComparingTo("100");
        assertThat(response.results().getFirst().metricState()).isEqualTo(MetricState.LIMITED);
        assertThat(response.results().getLast().metricState()).isEqualTo(MetricState.READY);
        assertThat(response.additionalSales().revenue().metricState()).isEqualTo(MetricState.LIMITED);
        assertThat(response.team().state()).isEqualTo(WeeklyReviewResponse.BlockState.LIMITED);
        assertThat(response.limitations()).singleElement().satisfies(item -> {
            assertThat(item.code()).isEqualTo("RETURN_EMPLOYEE_MISSING");
            assertThat(item.scope()).isEqualTo("SELLERS");
            assertThat(item.severity()).isEqualTo("WARNING");
            assertThat(item.affectedMetricCodes()).contains("RETURN_REVENUE", "NET_REVENUE");
        });
    }

    @Test
    void missingCurrentSourceCoverageMasksAllFinancialValues() {
        var response = new SellerWeeklyV3Assembler().assemble(
                source(PREVIOUS.end()), provenance(), SOURCE_HASH, NOW);

        assertThat(response.reportState()).isEqualTo(ReportState.BLOCKED);
        assertThat(response.results()).allSatisfy(item -> {
            assertThat(item.metricState()).isEqualTo(MetricState.UNAVAILABLE);
            assertThat(item.current()).isNull();
        });
        assertThat(response.additionalSales().revenue().current()).isNull();
        assertThat(response.employees()).isEmpty();
        assertThat(response.actions()).isEmpty();
        assertThat(response.limitations()).allMatch(item -> "SELLERS".equals(item.scope()));
        assertThat(new WeeklyReviewV3SnapshotCodec().deserialize(
                new WeeklyReviewV3SnapshotCodec().serialize(response))).isEqualTo(response);
    }

    @Test
    void inProgressSyncMasksValuesDespiteCompleteHistoricalCoverage() {
        SellerWeeklyReviewFacts facts = source(CURRENT.end());
        when(facts.sourceStability()).thenReturn(SellerWeeklySourceStability.IN_PROGRESS);

        var response = new SellerWeeklyV3Assembler().assemble(
                facts, provenance(), SOURCE_HASH, NOW);

        assertThat(response.reportState()).isEqualTo(ReportState.BLOCKED);
        assertThat(response.results()).allSatisfy(item -> {
            assertThat(item.metricState()).isEqualTo(MetricState.UNAVAILABLE);
            assertThat(item.current()).isNull();
        });
        assertThat(response.additionalSales().revenue().current()).isNull();
        assertThat(response.employees()).isEmpty();
        assertThat(response.actions()).isEmpty();
        assertThat(response.limitations()).anyMatch(item ->
                "SOURCE_SYNC_IN_PROGRESS".equals(item.code()));
    }

    @Test
    void presentationNameChangeDoesNotCreateNewSemanticContent() {
        var assembler = new SellerWeeklyV3Assembler();
        var first = assembler.assemble(source(CURRENT.end(), "Earlier label"),
                provenance(), SOURCE_HASH, NOW);
        var renamed = assembler.assemble(source(CURRENT.end(), "Updated label"),
                provenance(), SOURCE_HASH, NOW);
        var codec = new WeeklyReviewV3SnapshotCodec();

        assertThat(first.employees().getFirst().card().displayName())
                .isNotEqualTo(renamed.employees().getFirst().card().displayName());
        assertThat(codec.contentHash(first)).isEqualTo(codec.contentHash(renamed));
    }

    @Test
    void historicalAssemblyKeepsFinancialValuesButSeparatesSelectionAndDepartedActions() {
        var membership = new SellerWeeklyHistoricalMembership(Instant.parse("2026-08-01T00:00:00Z"),
                2, "c".repeat(64), "d".repeat(64));
        var facts = historicalFacts(STORE, new PeriodContext("Europe/Moscow", CURRENT, PREVIOUS,
                "Текущая", "Предыдущая"), false, 0, membership);
        var historical = new SellerWeeklyV3Assembler().assembleHistorical(facts, provenance(), SOURCE_HASH, NOW);
        var current = new SellerWeeklyV3Assembler().assemble(source(CURRENT.end()), provenance(), SOURCE_HASH, NOW);
        assertThat(historical.results()).isEqualTo(current.results());
        assertThat(historical.revenueDecomposition()).isEqualTo(current.revenueDecomposition());
        assertThat(historical.additionalSales()).isEqualTo(current.additionalSales());
        assertThat(historical.membership().basis()).isEqualTo(SellerWeeklyHistoricalMembership.BASIS);
        assertThat(historical.membership().currentCohortHash()).isEqualTo(membership.selectionHash());
        assertThat(historical.membership().actionabilityRosterHash()).isEqualTo(membership.actionabilityHash());
        assertThat(historical.employees()).singleElement().satisfies(item -> {
            assertThat(item.actionableNow()).isFalse();
            assertThat(item.card().action()).isNull();
            assertThat(item.card().limitations()).anyMatch(value -> value.startsWith("Не в текущей команде"));
        });
        assertThat(historical.evidence()).allMatch(item ->
                item.formulaVersion().equals(SellerWeeklyV3Assembler.historicalVersions().metricsPolicy()));
        var codec = new WeeklyReviewV3SnapshotCodec();
        String encoded = codec.serialize(historical);
        assertThat(codec.serialize(codec.deserialize(encoded))).isEqualTo(encoded);
        assertThat(codec.contentHash(codec.deserialize(encoded))).isEqualTo(codec.contentHash(historical));
        assertThat(codec.contentHash(historical)).isNotEqualTo(codec.contentHash(current));
    }

    @Test
    void oldHistoricalWeekDoesNotAssignNewFutureActionsToCurrentEmployees() {
        var membership = new SellerWeeklyHistoricalMembership(Instant.parse("2026-08-01T00:00:00Z"),
                2, "c".repeat(64), "d".repeat(64));
        var facts = historicalFacts(STORE, new PeriodContext("Europe/Moscow", CURRENT, PREVIOUS,
                "Текущая", "Предыдущая"), true, 0, membership);
        declineWithSufficientSample(facts);
        var latest = new SellerWeeklyV3Assembler().assembleHistorical(facts, provenance(), SOURCE_HASH, NOW);
        assertThat(latest.actions()).isNotEmpty();
        assertThat(latest.employees()).anyMatch(item -> item.card().action() != null);
        var response = new SellerWeeklyV3Assembler().assembleHistorical(facts, provenance(), SOURCE_HASH,
                NOW.plusSeconds(14 * 86400));
        assertThat(response.actions()).isEmpty();
        assertThat(response.employees()).singleElement().satisfies(item -> {
            assertThat(item.actionableNow()).isTrue();
            assertThat(item.card().action()).isNull();
            assertThat(item.card().limitations()).noneMatch(value -> value.startsWith("Не в текущей команде"));
        });
    }

    private void declineWithSufficientSample(SellerWeeklyHistoricalFacts facts) {
        var current = facts.historical().comparison().current();
        var groups = List.of(
                group("PHONES", new BigDecimal("95")), group("DEVICES", new BigDecimal("95")),
                group("ADDITIONAL_REVENUE", new BigDecimal("5")), group("ACCESSORY", new BigDecimal("2")),
                group("SERVICE", new BigDecimal("3")));
        when(current.metrics().categories().groups()).thenReturn(groups);
        var additional = mock(EmployeeCategoryKpiAggregate.class);
        when(additional.employeeId()).thenReturn(SELLER);
        when(additional.countsAsAdditionalRevenue()).thenReturn(true);
        when(additional.netRevenue()).thenReturn(new BigDecimal("5"));
        when(current.metrics().employeeCategories()).thenReturn(List.of(additional));
        for (var selected : List.of(current, facts.historical().comparison().previous())) {
            BigDecimal net = selected.metrics().totals().netRevenue();
            when(selected.documents()).thenReturn(List.of(new SellerDocumentAggregate(
                    SELLER, net, BigDecimal.ZERO, 20, 0, 20)));
        }
    }

    static SellerWeeklyHistoricalFacts historicalFacts(UUID storeId, PeriodContext period, boolean actionable,
            long revision, SellerWeeklyHistoricalMembership membership) {
        var fixture = new SellerWeeklyV3AssemblerTest();
        var source = fixture.source(CURRENT.end());
        var comparison = source.comparison();
        var cohort = new SellerCohortSnapshot(storeId, List.of(SELLER));
        when(source.sourceDataStatus().storeId()).thenReturn(storeId);
        when(comparison.current().metrics().cohort()).thenReturn(cohort);
        when(comparison.previous().metrics().cohort()).thenReturn(cohort);
        when(comparison.current().metrics().period()).thenReturn(
                new StoreKpiPeriod(period.current().start(), period.current().end()));
        when(comparison.previous().metrics().period()).thenReturn(
                new StoreKpiPeriod(period.previous().start(), period.previous().end()));
        when(comparison.current().projectedAttachRates().formulaVersion()).thenReturn("attach-rate-v4");
        when(comparison.previous().projectedAttachRates().formulaVersion()).thenReturn("attach-rate-v4");
        when(comparison.current().attachFormulaVersion()).thenReturn("attach-rate-v4-historical-membership-v1");
        when(comparison.previous().attachFormulaVersion()).thenReturn("attach-rate-v4-historical-membership-v1");
        return new SellerWeeklyHistoricalFacts(storeId, period, source.sourceDataStatus(),
                new SellerHistoricalComparisonFacts(comparison, actionable ? Set.of(SELLER) : Set.of()), NOW,
                SellerWeeklySourceStability.STABLE, SellerWeeklySourceCoverage.complete(), revision, membership);
    }

    private Provenance provenance() {
        return new Provenance("test-seller-v3", 1, NOW, NOW, false, null);
    }

    private SellerWeeklyReviewFacts source(LocalDate throughDate) {
        return source(throughDate, "Synthetic");
    }

    private SellerWeeklyReviewFacts source(LocalDate throughDate, String displayName) {
        StoreDataStatusView status = mock(StoreDataStatusView.class);
        when(status.salesDataThroughDate()).thenReturn(throughDate);
        when(status.returnsDataThroughDate()).thenReturn(throughDate);
        SellerPeriodComparisonFacts comparison = mock(SellerPeriodComparisonFacts.class);
        SellerPeriodFacts current = facts("100", displayName);
        SellerPeriodFacts previous = facts("80", displayName);
        when(comparison.current()).thenReturn(current);
        when(comparison.previous()).thenReturn(previous);
        SellerWeeklyReviewFacts source = mock(SellerWeeklyReviewFacts.class);
        when(source.sourceDataStatus()).thenReturn(status);
        when(source.sourceStability()).thenReturn(SellerWeeklySourceStability.STABLE);
        when(source.sourceCoverage()).thenReturn(new SellerWeeklySourceCoverage(
                new SellerWeeklySourceCoverage.Window(!throughDate.isBefore(CURRENT.end()), true),
                new SellerWeeklySourceCoverage.Window(!throughDate.isBefore(CURRENT.end()), true),
                new SellerWeeklySourceCoverage.Window(true, true)));
        when(source.period()).thenReturn(new PeriodContext("Europe/Moscow", CURRENT,
                PREVIOUS, "Текущая", "Предыдущая"));
        when(source.comparison()).thenReturn(comparison);
        return source;
    }

    private SellerPeriodFacts facts(String net, String displayName) {
        SellerPeriodFacts result = mock(SellerPeriodFacts.class);
        SellerPeriodMetrics metrics = mock(SellerPeriodMetrics.class);
        CategoryKpiResult categories = mock(CategoryKpiResult.class);
        CategoryKpiMetrics totals = mock(CategoryKpiMetrics.class);
        AttachRateResult attach = mock(AttachRateResult.class);
        when(result.metrics()).thenReturn(metrics);
        when(result.returnAttribution()).thenReturn(SellerReturnAttributionQuality.COMPLETE);
        when(result.projectedAttachRates()).thenReturn(attach);
        when(result.documents()).thenReturn(List.of(new SellerDocumentAggregate(
                SELLER, new BigDecimal(net), BigDecimal.ZERO, 1, 0, 1)));
        EmployeeKpiAggregate employee = new EmployeeKpiAggregate(SELLER, displayName,
                true, true, true, true, false, new BigDecimal(net), BigDecimal.ONE,
                BigDecimal.ZERO, 1, 0, 0, 0);
        EmployeeCategoryKpiAggregate additional = new EmployeeCategoryKpiAggregate(
                SELLER, displayName, true, true, true, true, false, "ACCESSORY", "Accessory",
                AnalyticsCategoryKind.ACCESSORY, DeviceFamily.NONE, true, false, false, true,
                new BigDecimal("20"), BigDecimal.ONE, BigDecimal.ZERO, 1, 0, 0);
        when(metrics.cohort()).thenReturn(COHORT);
        when(metrics.employees()).thenReturn(List.of(employee));
        when(metrics.employeeCategories()).thenReturn(List.of(additional));
        when(metrics.totals()).thenReturn(totals);
        when(metrics.categories()).thenReturn(categories);
        when(totals.netRevenue()).thenReturn(new BigDecimal(net));
        when(totals.grossProfit()).thenReturn(new BigDecimal(net));
        when(totals.marginPercent()).thenReturn(new BigDecimal("100"));
        when(totals.dataQuality()).thenReturn(new CategoryKpiDataQuality(true, 1, 0, 0));
        when(categories.categories()).thenReturn(List.of());
        List<CategoryKpiGroup> groups = List.of(
                group("PHONES", new BigDecimal(net).subtract(new BigDecimal("20"))),
                group("DEVICES", new BigDecimal(net).subtract(new BigDecimal("20"))),
                group("ADDITIONAL_REVENUE", new BigDecimal("20")),
                group("ACCESSORY", new BigDecimal("10")), group("SERVICE", new BigDecimal("10")));
        when(categories.groups()).thenReturn(groups);
        when(attach.dataQuality()).thenReturn(new AttachRateDataQuality(0, 0, 0));
        when(attach.formulaVersion()).thenReturn("attach-rate-v3");
        when(attach.rates()).thenReturn(List.of());
        return result;
    }

    private CategoryKpiGroup group(String code, BigDecimal money) {
        CategoryKpiMetrics metrics = mock(CategoryKpiMetrics.class);
        when(metrics.netRevenue()).thenReturn(money);
        return new CategoryKpiGroup(code, code, metrics);
    }
}
