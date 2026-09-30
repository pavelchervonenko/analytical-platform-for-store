package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.interpretation.review.WeeklyReviewPolicyV1.DeltaMode.ABSOLUTE;
import static com.storeanalytics.interpretation.review.WeeklyReviewPolicyV1.DeltaMode.RELATIVE;
import static com.storeanalytics.interpretation.review.WeeklyReviewPolicyV1.Polarity.HIGHER_IS_BETTER;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.Materiality.NOT_EVALUATED;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricState.LIMITED;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricState.READY;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricState.UNAVAILABLE;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.Sufficiency.INSUFFICIENT;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.Sufficiency.SUFFICIENT;

import com.storeanalytics.interpretation.review.WeeklyReviewPolicyV1.MetricSpec;
import com.storeanalytics.interpretation.review.WeeklyReviewPolicyV1.RevenuePeriod;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricComparison;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricState;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.RevenueDecomposition;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Sample;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Sufficiency;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Unit;
import com.storeanalytics.metrics.repository.SellerDocumentAggregate;
import com.storeanalytics.metrics.service.CategoryKpiMetrics;
import com.storeanalytics.metrics.service.SellerPeriodFacts;
import com.storeanalytics.metrics.service.StoreKpiResult;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Component;

/** Projects the four backend-owned result cards without consulting the AI provider. */
@Component
public final class WeeklyReviewCoreProjector {

    private static final BigDecimal SHARE_THRESHOLD = new BigDecimal("3.00");
    private static final BigDecimal STORE_THRESHOLD = new BigDecimal("5.00");

    private final WeeklyReviewPolicyV1 policy = new WeeklyReviewPolicyV1();

    public Projection project(
            StoreKpiResult current,
            StoreKpiResult previous,
            RevenuePeriod currentRevenue,
            RevenuePeriod previousRevenue
    ) {
        return project(current, previous, currentRevenue, previousRevenue, false);
    }

    public Projection project(
            StoreKpiResult current,
            StoreKpiResult previous,
            RevenuePeriod currentRevenue,
            RevenuePeriod previousRevenue,
            boolean sourceAnalyticsBlocked
    ) {
        return projectCore(
                CoreFinancial.fromStore(requireNonNull(current, "current")),
                CoreFinancial.fromStore(requireNonNull(previous, "previous")),
                currentRevenue, previousRevenue, sourceAnalyticsBlocked, "STORE");
    }

    public Projection projectSellers(
            SellerPeriodFacts current,
            SellerPeriodFacts previous,
            boolean sourceAnalyticsBlocked,
            boolean revenueQualityComplete
    ) {
        return projectSellers(current, previous, sourceAnalyticsBlocked, revenueQualityComplete, true);
    }

    public Projection projectSellers(
            SellerPeriodFacts current,
            SellerPeriodFacts previous,
            boolean sourceAnalyticsBlocked,
            boolean revenueQualityComplete,
            boolean returnAttributionComplete
    ) {
        SellerPeriodFacts currentFacts = requireNonNull(current, "current");
        SellerPeriodFacts previousFacts = requireNonNull(previous, "previous");
        if (sourceAnalyticsBlocked) {
            return unavailableProjection("SELLERS");
        }
        Projection result = projectCore(
                CoreFinancial.fromSeller(currentFacts.metrics().totals(), revenueQualityComplete),
                CoreFinancial.fromSeller(previousFacts.metrics().totals(), revenueQualityComplete),
                revenue(currentFacts), revenue(previousFacts), false, "SELLERS");
        return returnAttributionComplete ? result : limitReturnDependentSellerMetrics(result);
    }

    private Projection limitReturnDependentSellerMetrics(Projection result) {
        List<MetricComparison> cards = result.results();
        RevenueDecomposition revenue = result.revenueDecomposition();
        return new Projection(List.of(limit(cards.get(0)), limit(cards.get(1)),
                        limit(cards.get(2)), cards.get(3)),
                new RevenueDecomposition(revenue.salesRevenue(), limit(revenue.returnRevenue()),
                        limit(revenue.netRevenue()), revenue.saleDocumentCount(),
                        limit(revenue.returnDocumentCount()), revenue.identityValid()));
    }

    private MetricComparison limit(MetricComparison metric) {
        if (metric.metricState() == UNAVAILABLE) {
            return metric;
        }
        return new MetricComparison(metric.metricId(), metric.code(), metric.label(), metric.unit(),
                metric.current(), metric.previous(), metric.absoluteDelta(), metric.changePercent(),
                metric.comparisonKind(), metric.direction(), metric.effect(), LIMITED,
                Sufficiency.LIMITED, NOT_EVALUATED, metric.currentSample(), metric.previousSample(),
                metric.evidenceRefs());
    }

    private RevenuePeriod revenue(SellerPeriodFacts facts) {
        List<SellerDocumentAggregate> documents = facts.documents();
        return new RevenuePeriod(
                documents.stream().map(SellerDocumentAggregate::salesRevenue)
                        .reduce(BigDecimal.ZERO, BigDecimal::add),
                documents.stream().map(SellerDocumentAggregate::returnRevenue)
                        .reduce(BigDecimal.ZERO, BigDecimal::add),
                facts.metrics().totals().netRevenue(),
                documents.stream().mapToLong(SellerDocumentAggregate::saleDocumentCount).sum(),
                documents.stream().mapToLong(SellerDocumentAggregate::returnDocumentCount).sum());
    }

    private Projection projectCore(
            CoreFinancial currentKpi,
            CoreFinancial previousKpi,
            RevenuePeriod currentRevenue,
            RevenuePeriod previousRevenue,
            boolean sourceAnalyticsBlocked,
            String scope
    ) {
        RevenuePeriod currentBreakdown = requireNonNull(currentRevenue, "currentRevenue");
        RevenuePeriod previousBreakdown = requireNonNull(previousRevenue, "previousRevenue");
        require(currentKpi.netRevenue().compareTo(currentBreakdown.netRevenue()) == 0,
                "current KPI must match revenue decomposition");
        require(previousKpi.netRevenue().compareTo(previousBreakdown.netRevenue()) == 0,
                "previous KPI must match revenue decomposition");
        if (sourceAnalyticsBlocked) {
            return unavailableProjection(scope);
        }

        boolean revenueQualityComplete = currentKpi.revenueQualityComplete()
                && previousKpi.revenueQualityComplete();
        MetricComparison netRevenue = policy.compare(
                moneySpec(scope, "NET_REVENUE", "Чистая выручка"),
                currentKpi.netRevenue(),
                previousKpi.netRevenue(),
                revenueQualityComplete ? READY : LIMITED,
                revenueQualityComplete ? SUFFICIENT : Sufficiency.LIMITED,
                null,
                null
        );
        CostState costState = costState(currentKpi, previousKpi);
        boolean sellerCoverageLimited = "SELLERS".equals(scope) && !revenueQualityComplete;
        MetricComparison grossProfit = policy.compare(
                moneySpec(scope, "GROSS_PROFIT", "Валовая прибыль"),
                currentKpi.grossProfit(),
                previousKpi.grossProfit(),
                sellerCoverageLimited && costState.metricState() == READY ? LIMITED
                        : costState.metricState(),
                sellerCoverageLimited && costState.sufficiency() == SUFFICIENT
                        ? Sufficiency.LIMITED : costState.sufficiency(),
                null,
                null
        );
        MetricComparison margin = policy.compare(
                new MetricSpec(
                        scope.toLowerCase(java.util.Locale.ROOT) + ":margin-percent",
                        "MARGIN_PERCENT",
                        "Маржа",
                        Unit.PERCENT,
                        HIGHER_IS_BETTER,
                        ABSOLUTE,
                        SHARE_THRESHOLD,
                        scope + ".MARGIN_PERCENT"
                ),
                currentKpi.marginPercent(),
                previousKpi.marginPercent(),
                sellerCoverageLimited && marginState(currentKpi, previousKpi, costState) == READY
                        ? LIMITED : marginState(currentKpi, previousKpi, costState),
                sellerCoverageLimited
                        && marginSufficiency(currentKpi, previousKpi, costState) == SUFFICIENT
                        ? Sufficiency.LIMITED : marginSufficiency(currentKpi, previousKpi, costState),
                null,
                null
        );
        BigDecimal currentAverage = policy.averageSale(currentBreakdown);
        BigDecimal previousAverage = policy.averageSale(previousBreakdown);
        MetricState averageState = currentAverage == null || previousAverage == null
                ? UNAVAILABLE : sellerCoverageLimited ? LIMITED : READY;
        Sufficiency averageSufficiency = averageState == READY
                ? SUFFICIENT : averageState == LIMITED ? Sufficiency.LIMITED : INSUFFICIENT;
        MetricComparison averageSale = policy.compare(
                moneySpec(scope, "AVERAGE_SALE", "Средняя продажа"),
                currentAverage,
                previousAverage,
                averageState,
                averageSufficiency,
                saleSample(currentBreakdown),
                saleSample(previousBreakdown)
        );
        RevenueDecomposition decomposition = policy.revenueDecomposition(
                currentBreakdown,
                previousBreakdown,
                revenueQualityComplete ? READY : LIMITED,
                revenueQualityComplete ? SUFFICIENT : Sufficiency.LIMITED,
                scope
        );
        return new Projection(
                List.of(netRevenue, grossProfit, margin, averageSale),
                decomposition
        );
    }

    private Projection unavailableProjection(String scope) {
        MetricComparison netRevenue = unavailable(
                moneySpec(scope, "NET_REVENUE", "Чистая выручка")
        );
        MetricComparison grossProfit = unavailable(
                moneySpec(scope, "GROSS_PROFIT", "Валовая прибыль")
        );
        MetricComparison margin = unavailable(new MetricSpec(
                scope.toLowerCase(java.util.Locale.ROOT) + ":margin-percent",
                "MARGIN_PERCENT",
                "Маржа",
                Unit.PERCENT,
                HIGHER_IS_BETTER,
                ABSOLUTE,
                SHARE_THRESHOLD,
                scope + ".MARGIN_PERCENT"
        ));
        MetricComparison averageSale = unavailable(
                moneySpec(scope, "AVERAGE_SALE", "Средняя продажа")
        );
        RevenueDecomposition decomposition = new RevenueDecomposition(
                unavailable(MetricSpec.money(
                        scope, "SALES_REVENUE", WeeklyReviewPolicyV1.Polarity.HIGHER_IS_BETTER
                )),
                unavailable(MetricSpec.money(
                        scope, "RETURN_REVENUE", WeeklyReviewPolicyV1.Polarity.LOWER_IS_BETTER
                )),
                unavailable(MetricSpec.money(
                        scope, "NET_REVENUE", WeeklyReviewPolicyV1.Polarity.HIGHER_IS_BETTER
                )),
                unavailable(MetricSpec.count(scope, "SALE_DOCUMENT_COUNT")),
                unavailable(MetricSpec.count(scope, "RETURN_DOCUMENT_COUNT")),
                true
        );
        return new Projection(
                List.of(netRevenue, grossProfit, margin, averageSale),
                decomposition
        );
    }

    private MetricComparison unavailable(MetricSpec spec) {
        return policy.compare(
                spec,
                null,
                null,
                UNAVAILABLE,
                INSUFFICIENT,
                null,
                null
        );
    }

    private MetricSpec moneySpec(String scope, String code, String label) {
        return new MetricSpec(
                scope.toLowerCase(java.util.Locale.ROOT) + ":" + code.toLowerCase(java.util.Locale.ROOT),
                code,
                label,
                Unit.RUB,
                HIGHER_IS_BETTER,
                RELATIVE,
                STORE_THRESHOLD,
                scope + "." + code
        );
    }

    private CostState costState(CoreFinancial current, CoreFinancial previous) {
        if (!current.completeCostData() || !previous.completeCostData()) {
            return new CostState(UNAVAILABLE, INSUFFICIENT);
        }
        return new CostState(READY, SUFFICIENT);
    }

    private MetricState marginState(
            CoreFinancial current,
            CoreFinancial previous,
            CostState costState
    ) {
        if (current.marginPercent() == null || previous.marginPercent() == null) {
            return UNAVAILABLE;
        }
        return costState.metricState();
    }

    private Sufficiency marginSufficiency(
            CoreFinancial current,
            CoreFinancial previous,
            CostState costState
    ) {
        return current.marginPercent() == null || previous.marginPercent() == null
                ? INSUFFICIENT
                : costState.sufficiency();
    }

    private Sample saleSample(RevenuePeriod revenue) {
        return new Sample(
                revenue.salesRevenue(),
                BigDecimal.valueOf(revenue.saleDocumentCount()),
                "Выручка продаж",
                "Завершённые продажи"
        );
    }

    public record Projection(
            List<MetricComparison> results,
            WeeklyReviewResponse.RevenueDecomposition revenueDecomposition
    ) {

        public Projection {
            results = List.copyOf(requireNonNull(results, "results"));
            require(results.size() == 4, "results must contain four metrics");
            requireNonNull(revenueDecomposition, "revenueDecomposition");
        }
    }

    private record CostState(MetricState metricState, Sufficiency sufficiency) {
    }

    private record CoreFinancial(
            BigDecimal netRevenue,
            BigDecimal grossProfit,
            BigDecimal marginPercent,
            boolean completeCostData,
            boolean revenueQualityComplete
    ) {
        private static CoreFinancial fromStore(StoreKpiResult result) {
            return new CoreFinancial(result.netRevenue(), result.grossProfit(),
                    result.marginPercent(), result.dataQuality().completeCostData(),
                    result.dataQuality().periodOpenConsistencyIssueCount() == 0);
        }

        private static CoreFinancial fromSeller(
                CategoryKpiMetrics metrics, boolean revenueQualityComplete
        ) {
            return new CoreFinancial(metrics.netRevenue(), metrics.grossProfit(),
                    metrics.marginPercent(), metrics.dataQuality().completeCostData(),
                    revenueQualityComplete);
        }
    }
}
