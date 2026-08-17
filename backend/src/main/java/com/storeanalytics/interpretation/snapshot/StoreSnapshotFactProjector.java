package com.storeanalytics.interpretation.snapshot;

import static com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Materiality.CONTEXT;
import static com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Materiality.PRIMARY;
import static com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Materiality.SECONDARY;
import static com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Sufficiency.LIMITED;
import static com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Sufficiency.SUFFICIENT;
import static com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Unit.COUNT;
import static com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Unit.MONEY;
import static com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Unit.PERCENT;
import static com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Unit.RATE_PER_HUNDRED;
import static com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Unit.STATUS;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Fact;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Materiality;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Sufficiency;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Unit;
import com.storeanalytics.metrics.service.AttachRateEntry;
import com.storeanalytics.metrics.service.AverageMetricComparison;
import com.storeanalytics.metrics.service.CategoryKpiEntry;
import com.storeanalytics.metrics.service.CategoryKpiGroup;
import com.storeanalytics.metrics.service.CategoryKpiMetrics;
import com.storeanalytics.metrics.service.StoreKpiResult;
import com.storeanalytics.performance.service.StorePlanDirectionView;
import com.storeanalytics.performance.service.StorePlanProgressView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

final class StoreSnapshotFactProjector {

    private final WeeklySnapshotPolicyV1 policy;

    StoreSnapshotFactProjector(WeeklySnapshotPolicyV1 policy) {
        this.policy = policy;
    }

    List<Fact> project(WeeklyAnalyticsFacts source) {
        List<Fact> result = new ArrayList<>();
        addStoreKpis(result, source.current().store(), source.previous().store());
        addAverages(result, source);
        addGroups(result, source);
        addCategories(result, source);
        addAttachRates(result, source);
        addPlan(result, source);
        return result.stream()
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(Fact::evidenceRef))
                .toList();
    }

    private void addStoreKpis(List<Fact> target, StoreKpiResult current, StoreKpiResult previous) {
        target.add(metric("NET_REVENUE", MONEY, current.netRevenue(), previous.netRevenue(), PRIMARY));
        target.add(metric("NET_QUANTITY", COUNT, current.netQuantity(), previous.netQuantity(), SECONDARY));
        target.add(metric("COST_AMOUNT", MONEY, current.costAmount(), previous.costAmount(), CONTEXT));
        target.add(metric("GROSS_PROFIT", MONEY, current.grossProfit(), previous.grossProfit(), PRIMARY));
        target.add(SnapshotFactFactory.numeric(
                "STORE.MARGIN_PERCENT.CURRENT",
                "MARGIN_PERCENT",
                current.marginPercent(),
                previous.marginPercent(),
                new SnapshotFactFactory.FactOptions(
                        null,
                        PERCENT,
                        SUFFICIENT,
                        SECONDARY,
                        false
                )
        ));
    }

    private Fact metric(
            String code,
            com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Unit unit,
            BigDecimal current,
            BigDecimal previous,
            com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Materiality materiality
    ) {
        return SnapshotFactFactory.numeric(
                "STORE." + code + ".CURRENT",
                code,
                current,
                previous,
                new SnapshotFactFactory.FactOptions(
                        null,
                        unit,
                        SUFFICIENT,
                        materiality,
                        true
                )
        );
    }

    private void addAverages(List<Fact> target, WeeklyAnalyticsFacts source) {
        addAverage(target, "AVERAGE_RECEIPT", source.averageComparisons().averageReceipt());
        addAverage(
                target,
                "ADDITIONAL_REVENUE_PER_PHONE",
                source.averageComparisons().additionalRevenuePerPhone()
        );
    }

    private void addAverage(
            List<Fact> target,
            String code,
            AverageMetricComparison comparison
    ) {
        if (comparison == null || comparison.current() == null) {
            return;
        }
        BigDecimal previous = comparison.previous() == null
                ? null
                : comparison.previous().value();
        target.add(SnapshotFactFactory.numeric(
                "STORE." + code + ".CURRENT",
                code,
                comparison.current().value(),
                previous,
                new SnapshotFactFactory.FactOptions(
                        null,
                        MONEY,
                        SUFFICIENT,
                        SECONDARY,
                        true
                )
        ));
    }

    private void addGroups(List<Fact> target, WeeklyAnalyticsFacts source) {
        Map<String, CategoryKpiGroup> previous = source.previous().categories().groups().stream()
                .collect(Collectors.toMap(CategoryKpiGroup::groupCode, Function.identity()));
        BigDecimal currentRevenue = source.current().store().netRevenue();
        BigDecimal previousRevenue = source.previous().store().netRevenue();
        source.current().categories().groups().stream()
                .filter(group -> hasActivity(
                        group.metrics(),
                        metrics(previous.get(group.groupCode()))
                ))
                .sorted(Comparator.comparing(CategoryKpiGroup::groupCode))
                .forEach(group -> addCategoryMetrics(
                        target,
                        "STORE.GROUP:" + group.groupCode() + ".",
                        null,
                        group.metrics(),
                        metrics(previous.get(group.groupCode())),
                        currentRevenue,
                        previousRevenue
                ));
    }

    private void addCategories(List<Fact> target, WeeklyAnalyticsFacts source) {
        Map<String, CategoryKpiEntry> previous = source.previous().categories().categories().stream()
                .collect(Collectors.toMap(CategoryKpiEntry::categoryCode, Function.identity()));
        BigDecimal currentRevenue = source.current().store().netRevenue();
        BigDecimal previousRevenue = source.previous().store().netRevenue();
        source.current().categories().categories().stream()
                .filter(category -> hasActivity(
                        category.metrics(),
                        metrics(previous.get(category.categoryCode()))
                ))
                .sorted(Comparator.comparing(CategoryKpiEntry::categoryCode))
                .forEach(category -> addCategoryMetrics(
                        target,
                        "STORE.CATEGORY:" + category.categoryCode() + ".",
                        category.categoryCode(),
                        category.metrics(),
                        metrics(previous.get(category.categoryCode())),
                        currentRevenue,
                        previousRevenue
                ));
    }

    private void addCategoryMetrics(
            List<Fact> target,
            String prefix,
            String categoryCode,
            CategoryKpiMetrics current,
            CategoryKpiMetrics previous,
            BigDecimal currentStoreRevenue,
            BigDecimal previousStoreRevenue
    ) {
        target.add(SnapshotFactFactory.numeric(
                prefix + "NET_REVENUE.CURRENT",
                "NET_REVENUE",
                current.netRevenue(),
                value(previous, CategoryKpiMetrics::netRevenue),
                new SnapshotFactFactory.FactOptions(
                        categoryCode,
                        MONEY,
                        SUFFICIENT,
                        SECONDARY,
                        true
                )
        ));
        target.add(SnapshotFactFactory.numeric(
                prefix + "NET_QUANTITY.CURRENT",
                "NET_QUANTITY",
                current.netQuantity(),
                value(previous, CategoryKpiMetrics::netQuantity),
                new SnapshotFactFactory.FactOptions(
                        categoryCode,
                        COUNT,
                        SUFFICIENT,
                        CONTEXT,
                        true
                )
        ));
        target.add(SnapshotFactFactory.numeric(
                prefix + "REVENUE_SHARE_PERCENT.CURRENT",
                "REVENUE_SHARE_PERCENT",
                percentage(current.netRevenue(), currentStoreRevenue),
                previous == null ? null : percentage(previous.netRevenue(), previousStoreRevenue),
                new SnapshotFactFactory.FactOptions(
                        categoryCode,
                        PERCENT,
                        SUFFICIENT,
                        SECONDARY,
                        false
                )
        ));
    }

    private void addAttachRates(List<Fact> target, WeeklyAnalyticsFacts source) {
        Map<String, AttachRateEntry> previous = source.previous().attachRates().rates().stream()
                .collect(Collectors.toMap(AttachRateEntry::metricCode, Function.identity()));
        source.current().attachRates().rates().stream()
                .sorted(Comparator.comparing(AttachRateEntry::metricCode))
                .forEach(rate -> {
                    AttachRateEntry before = previous.get(rate.metricCode());
                    var sufficiency = policy.attach(rate.denominatorReceiptCount());
                    String prefix = "STORE.ATTACH:" + rate.metricCode() + ".";
                    target.add(SnapshotFactFactory.numeric(
                            prefix + "NUMERATOR_QUANTITY.CURRENT",
                            "NUMERATOR_QUANTITY",
                            rate.numeratorReceiptCount(),
                            before == null ? null : before.numeratorReceiptCount(),
                            new SnapshotFactFactory.FactOptions(
                                    rate.numeratorCategoryCode(),
                                    COUNT,
                                    sufficiency,
                                    CONTEXT,
                                    true
                            )
                    ));
                    target.add(SnapshotFactFactory.numeric(
                            prefix + "DENOMINATOR_QUANTITY.CURRENT",
                            "DENOMINATOR_QUANTITY",
                            rate.denominatorReceiptCount(),
                            before == null ? null : before.denominatorReceiptCount(),
                            new SnapshotFactFactory.FactOptions(
                                    rate.numeratorCategoryCode(),
                                    COUNT,
                                    sufficiency,
                                    CONTEXT,
                                    true
                            )
                    ));
                    target.add(SnapshotFactFactory.numeric(
                            prefix + "RATE_PER_HUNDRED.CURRENT",
                            "RATE_PER_HUNDRED",
                            rate.ratePerHundred(),
                            before == null ? null : before.ratePerHundred(),
                            new SnapshotFactFactory.FactOptions(
                                    rate.numeratorCategoryCode(),
                                    RATE_PER_HUNDRED,
                                    sufficiency,
                                    SECONDARY,
                                    false
                            )
                    ));
                });
    }

    private void addPlan(List<Fact> target, WeeklyAnalyticsFacts source) {
        source.planContexts().stream()
                .max(Comparator.comparing(StorePlanProgressView::asOfDate))
                .ifPresent(plan -> plan.directions().stream()
                        .sorted(Comparator.comparing(direction -> direction.code().name()))
                        .forEach(direction -> addPlanDirection(target, plan, direction)));
    }

    private void addPlanDirection(
            List<Fact> target,
            StorePlanProgressView plan,
            StorePlanDirectionView direction
    ) {
        Sufficiency sufficiency = plan.dataQuality().completeThroughAsOf()
                ? SUFFICIENT
                : LIMITED;
        String prefix = "STORE.PLAN:" + direction.code().name() + ".";
        target.add(planMetric(prefix, "ACTUAL_AMOUNT", MONEY,
                direction.actualAmount(), sufficiency, PRIMARY));
        target.add(planMetric(prefix, "TARGET_AMOUNT", MONEY,
                direction.targetAmount(), sufficiency, CONTEXT));
        target.add(planMetric(prefix, "AMOUNT_COMPLETION_PERCENT", PERCENT,
                direction.amountCompletionPercent(), sufficiency, SECONDARY));
        target.add(planMetric(prefix, "PROJECTED_AMOUNT", MONEY,
                direction.projectedAmount(), sufficiency, PRIMARY));
        target.add(planMetric(prefix, "PROJECTED_COMPLETION_PERCENT", PERCENT,
                direction.projectedAmountCompletionPercent(), sufficiency, PRIMARY));
        target.add(planMetric(prefix, "REMAINING_AMOUNT", MONEY,
                direction.remainingAmount(), sufficiency, SECONDARY));
        target.add(planMetric(prefix, "REQUIRED_PER_REMAINING_DAY", MONEY,
                direction.requiredPerRemainingDay(), sufficiency, SECONDARY));
        target.add(planMetric(prefix, "ACTUAL_SHARE_PERCENT", PERCENT,
                direction.actualSharePercent(), sufficiency, SECONDARY));
        target.add(planMetric(prefix, "TARGET_SHARE_PERCENT", PERCENT,
                direction.targetSharePercent(), sufficiency, CONTEXT));
        target.add(planMetric(prefix, "SHARE_GAP_PERCENTAGE_POINTS", PERCENT,
                direction.shareGapPercentagePoints(), sufficiency, SECONDARY));
        target.add(planMetric(prefix, "CRITERION_COMPLETION_PERCENT", PERCENT,
                direction.criterionCompletionPercent(), sufficiency, SECONDARY));
        target.add(new Fact(
                prefix + "STATUS",
                "PLAN_STATUS",
                null,
                STATUS,
                direction.status().name(),
                null,
                sufficiency,
                CONTEXT
        ));
    }

    private Fact planMetric(
            String prefix,
            String code,
            Unit unit,
            BigDecimal value,
            Sufficiency sufficiency,
            Materiality materiality
    ) {
        return SnapshotFactFactory.numeric(
                prefix + code,
                "PLAN_" + code,
                value,
                null,
                new SnapshotFactFactory.FactOptions(
                        null,
                        unit,
                        sufficiency,
                        materiality,
                        false
                )
        );
    }

    private static boolean hasActivity(
            CategoryKpiMetrics current,
            CategoryKpiMetrics previous
    ) {
        return current.dataQuality().includedItemCount() > 0
                || previous != null
                && previous.dataQuality().includedItemCount() > 0;
    }

    private static CategoryKpiMetrics metrics(CategoryKpiGroup group) {
        return group == null ? null : group.metrics();
    }

    private static CategoryKpiMetrics metrics(CategoryKpiEntry category) {
        return category == null ? null : category.metrics();
    }

    private static BigDecimal value(
            CategoryKpiMetrics metrics,
            Function<CategoryKpiMetrics, BigDecimal> getter
    ) {
        return metrics == null ? null : getter.apply(metrics);
    }

    private static BigDecimal percentage(BigDecimal numerator, BigDecimal denominator) {
        return denominator == null || denominator.signum() == 0
                ? null
                : numerator.multiply(BigDecimal.valueOf(100))
                        .divide(denominator, 4, RoundingMode.HALF_UP);
    }
}
