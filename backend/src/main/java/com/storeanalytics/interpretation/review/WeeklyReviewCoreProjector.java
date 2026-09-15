package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.interpretation.review.WeeklyReviewPolicyV1.DeltaMode.ABSOLUTE;
import static com.storeanalytics.interpretation.review.WeeklyReviewPolicyV1.DeltaMode.RELATIVE;
import static com.storeanalytics.interpretation.review.WeeklyReviewPolicyV1.Polarity.HIGHER_IS_BETTER;
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
        StoreKpiResult currentKpi = requireNonNull(current, "current");
        StoreKpiResult previousKpi = requireNonNull(previous, "previous");
        RevenuePeriod currentBreakdown = requireNonNull(currentRevenue, "currentRevenue");
        RevenuePeriod previousBreakdown = requireNonNull(previousRevenue, "previousRevenue");
        require(currentKpi.netRevenue().compareTo(currentBreakdown.netRevenue()) == 0,
                "current store KPI must match revenue decomposition");
        require(previousKpi.netRevenue().compareTo(previousBreakdown.netRevenue()) == 0,
                "previous store KPI must match revenue decomposition");
        if (sourceAnalyticsBlocked) {
            return unavailableProjection();
        }

        boolean revenueQualityComplete = currentKpi.dataQuality()
                .periodOpenConsistencyIssueCount() == 0
                && previousKpi.dataQuality().periodOpenConsistencyIssueCount() == 0;
        MetricComparison netRevenue = policy.compare(
                moneySpec("NET_REVENUE", "Чистая выручка", "STORE.NET_REVENUE"),
                currentKpi.netRevenue(),
                previousKpi.netRevenue(),
                revenueQualityComplete ? READY : LIMITED,
                revenueQualityComplete ? SUFFICIENT : Sufficiency.LIMITED,
                null,
                null
        );
        CostState costState = costState(currentKpi, previousKpi);
        MetricComparison grossProfit = policy.compare(
                moneySpec("GROSS_PROFIT", "Валовая прибыль", "STORE.GROSS_PROFIT"),
                currentKpi.grossProfit(),
                previousKpi.grossProfit(),
                costState.metricState(),
                costState.sufficiency(),
                null,
                null
        );
        MetricComparison margin = policy.compare(
                new MetricSpec(
                        "store:margin-percent",
                        "MARGIN_PERCENT",
                        "Маржа",
                        Unit.PERCENT,
                        HIGHER_IS_BETTER,
                        ABSOLUTE,
                        SHARE_THRESHOLD,
                        "STORE.MARGIN_PERCENT"
                ),
                currentKpi.marginPercent(),
                previousKpi.marginPercent(),
                marginState(currentKpi, previousKpi, costState),
                marginSufficiency(currentKpi, previousKpi, costState),
                null,
                null
        );
        BigDecimal currentAverage = policy.averageSale(currentBreakdown);
        BigDecimal previousAverage = policy.averageSale(previousBreakdown);
        MetricState averageState = currentAverage == null || previousAverage == null
                ? UNAVAILABLE
                : READY;
        Sufficiency averageSufficiency = averageState == READY
                ? SUFFICIENT
                : INSUFFICIENT;
        MetricComparison averageSale = policy.compare(
                moneySpec("AVERAGE_SALE", "Средняя продажа", "STORE.AVERAGE_SALE"),
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
                revenueQualityComplete ? SUFFICIENT : Sufficiency.LIMITED
        );
        return new Projection(
                List.of(netRevenue, grossProfit, margin, averageSale),
                decomposition
        );
    }

    private Projection unavailableProjection() {
        MetricComparison netRevenue = unavailable(
                moneySpec("NET_REVENUE", "Чистая выручка", "STORE.NET_REVENUE")
        );
        MetricComparison grossProfit = unavailable(
                moneySpec("GROSS_PROFIT", "Валовая прибыль", "STORE.GROSS_PROFIT")
        );
        MetricComparison margin = unavailable(new MetricSpec(
                "store:margin-percent",
                "MARGIN_PERCENT",
                "Маржа",
                Unit.PERCENT,
                HIGHER_IS_BETTER,
                ABSOLUTE,
                SHARE_THRESHOLD,
                "STORE.MARGIN_PERCENT"
        ));
        MetricComparison averageSale = unavailable(
                moneySpec("AVERAGE_SALE", "Средняя продажа", "STORE.AVERAGE_SALE")
        );
        RevenueDecomposition decomposition = new RevenueDecomposition(
                unavailable(MetricSpec.storeMoney(
                        "SALES_REVENUE", WeeklyReviewPolicyV1.Polarity.HIGHER_IS_BETTER
                )),
                unavailable(MetricSpec.storeMoney(
                        "RETURN_REVENUE", WeeklyReviewPolicyV1.Polarity.LOWER_IS_BETTER
                )),
                unavailable(MetricSpec.storeMoney(
                        "NET_REVENUE", WeeklyReviewPolicyV1.Polarity.HIGHER_IS_BETTER
                )),
                unavailable(MetricSpec.storeCount("SALE_DOCUMENT_COUNT")),
                unavailable(MetricSpec.storeCount("RETURN_DOCUMENT_COUNT")),
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

    private MetricSpec moneySpec(String code, String label, String evidenceRef) {
        return new MetricSpec(
                "store:" + code.toLowerCase(java.util.Locale.ROOT),
                code,
                label,
                Unit.RUB,
                HIGHER_IS_BETTER,
                RELATIVE,
                STORE_THRESHOLD,
                evidenceRef
        );
    }

    private CostState costState(StoreKpiResult current, StoreKpiResult previous) {
        if (!current.dataQuality().completeCostData()
                || !previous.dataQuality().completeCostData()) {
            return new CostState(UNAVAILABLE, INSUFFICIENT);
        }
        return new CostState(READY, SUFFICIENT);
    }

    private MetricState marginState(
            StoreKpiResult current,
            StoreKpiResult previous,
            CostState costState
    ) {
        if (current.marginPercent() == null || previous.marginPercent() == null) {
            return UNAVAILABLE;
        }
        return costState.metricState();
    }

    private Sufficiency marginSufficiency(
            StoreKpiResult current,
            StoreKpiResult previous,
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
}
