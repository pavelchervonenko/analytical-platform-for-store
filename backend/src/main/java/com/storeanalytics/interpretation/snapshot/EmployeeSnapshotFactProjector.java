package com.storeanalytics.interpretation.snapshot;

import static com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Materiality.CONTEXT;
import static com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Materiality.PRIMARY;
import static com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Materiality.SECONDARY;
import static com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Unit.COUNT;
import static com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Unit.HOURS;
import static com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Unit.MONEY;
import static com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Unit.PERCENT;
import static com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Unit.RANK;
import static com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Unit.RATE_PER_HUNDRED;
import static com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Unit.SCORE;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.EmployeeFacts;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Fact;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Materiality;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Sufficiency;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Unit;
import com.storeanalytics.metrics.service.EmployeeCategoryKpiEmployee;
import com.storeanalytics.metrics.service.EmployeeCategoryKpiEntry;
import com.storeanalytics.metrics.service.EmployeeCategoryKpiGroup;
import com.storeanalytics.metrics.service.EmployeeCategoryKpiMetrics;
import com.storeanalytics.metrics.service.EmployeeKpiEntry;
import com.storeanalytics.performance.service.EmployeeAttachRatingEntry;
import com.storeanalytics.performance.service.EmployeeRatingEntry;
import com.storeanalytics.performance.service.RatingScoreBreakdown;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

final class EmployeeSnapshotFactProjector {

    private final WeeklySnapshotPolicyV2 policy;

    EmployeeSnapshotFactProjector(WeeklySnapshotPolicyV2 policy) {
        this.policy = policy;
    }

    List<EmployeeFacts> project(
            WeeklyAnalyticsFacts source,
            List<SnapshotEmployeeMembership> memberships
    ) {
        PeriodIndex current = PeriodIndex.of(source.current());
        PeriodIndex previous = PeriodIndex.of(source.previous());
        return memberships.stream()
                .sorted(Comparator.comparing(SnapshotEmployeeMembership::employeeRef))
                .map(member -> projectEmployee(member, current, previous))
                .toList();
    }

    private EmployeeFacts projectEmployee(
            SnapshotEmployeeMembership member,
            PeriodIndex current,
            PeriodIndex previous
    ) {
        UUID employeeId = member.employeeId();
        String employeeRef = member.employeeRef();
        EmployeeRatingEntry rating = current.ratings().get(employeeId);
        EmployeeRatingEntry previousRating = previous.ratings().get(employeeId);
        EmployeeKpiEntry kpi = current.kpis().get(employeeId);
        EmployeeKpiEntry previousKpi = previous.kpis().get(employeeId);
        EmployeeCategoryKpiEmployee categories = current.categories().get(employeeId);
        EmployeeCategoryKpiEmployee previousCategories = previous.categories().get(employeeId);
        long completedSales = current.salesSamples().completedSales(employeeId);
        long previousCompletedSales = previous.salesSamples().completedSales(employeeId);

        Sufficiency workload = rating == null
                ? Sufficiency.INSUFFICIENT
                : policy.workload(rating.shiftCount(), rating.workedHours());
        Sufficiency previousWorkload = previousRating == null
                ? Sufficiency.INSUFFICIENT
                : policy.workload(previousRating.shiftCount(), previousRating.workedHours());
        boolean criticalQualityIssue = kpi == null;
        BigDecimal coverage = rating == null || rating.scores() == null
                ? null
                : rating.scores().coveragePercent();
        Sufficiency overall = policy.overall(coverage, workload, criticalQualityIssue);
        Sufficiency salesStructure = policy.salesStructure(BigDecimal.valueOf(completedSales));
        Sufficiency categorySufficiency = policy.mostRestrictive(overall, salesStructure);
        List<Fact> facts = new ArrayList<>();

        addWorkload(facts, employeeRef, rating, previousRating, workload);
        addSalesSample(facts, employeeRef, completedSales, previousCompletedSales,
                salesStructure);
        addFinancial(facts, employeeRef, kpi, previousKpi, overall);
        addEfficiency(facts, employeeRef, rating, previousRating, workload, previousWorkload);
        addRating(facts, employeeRef, rating, previousRating, overall);
        addAttach(facts, employeeRef, rating, previousRating);
        addCategories(facts, employeeRef, categories, previousCategories, categorySufficiency);

        List<Fact> sortedFacts = facts.stream()
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(Fact::evidenceRef))
                .toList();
        List<String> sections = availableSections(sortedFacts);
        return new EmployeeFacts(employeeRef, overall, sections, sortedFacts);
    }

    private void addWorkload(
            List<Fact> target,
            String employeeRef,
            EmployeeRatingEntry current,
            EmployeeRatingEntry previous,
            Sufficiency sufficiency
    ) {
        String prefix = "EMP:" + employeeRef + ".WORKLOAD.";
        long shifts = current == null ? 0 : current.shiftCount();
        Long previousShifts = previous == null ? null : previous.shiftCount();
        target.add(SnapshotFactFactory.count(
                prefix + "SHIFT_COUNT.CURRENT",
                "SHIFT_COUNT",
                shifts,
                previousShifts,
                sufficiency,
                CONTEXT
        ));
        target.add(SnapshotFactFactory.numeric(
                prefix + "WORKED_HOURS.CURRENT",
                "WORKED_HOURS",
                current == null ? BigDecimal.ZERO : current.workedHours(),
                previous == null ? null : previous.workedHours(),
                new SnapshotFactFactory.FactOptions(
                        null,
                        HOURS,
                        sufficiency,
                        CONTEXT,
                        true
                )
        ));
        target.add(SnapshotFactFactory.status(
                prefix + "STATUS",
                "WORKLOAD_STATUS",
                sufficiency.name(),
                sufficiency
        ));
    }

    private void addSalesSample(
            List<Fact> target,
            String employeeRef,
            long completedSales,
            long previousCompletedSales,
            Sufficiency salesStructure
    ) {
        target.add(SnapshotFactFactory.count(
                "EMP:" + employeeRef + ".SALES.COMPLETED_SALES.CURRENT",
                "COMPLETED_SALES",
                completedSales,
                previousCompletedSales,
                salesStructure,
                CONTEXT
        ));
        target.add(SnapshotFactFactory.status(
                "EMP:" + employeeRef + ".SALES_STRUCTURE.STATUS",
                "SALES_STRUCTURE_STATUS",
                salesStructure.name(),
                salesStructure
        ));

    }
    private void addFinancial(
            List<Fact> target,
            String employeeRef,
            EmployeeKpiEntry current,
            EmployeeKpiEntry previous,
            Sufficiency sufficiency
    ) {
        if (current == null) {
            return;
        }
        addMetric(
                target,
                employeeRef,
                "NET_REVENUE",
                current.netRevenue(),
                value(previous, EmployeeKpiEntry::netRevenue),
                new EmployeeMetricOptions(
                        MONEY,
                        sufficiency,
                        PRIMARY,
                        true
                )
        );
        addMetric(
                target,
                employeeRef,
                "NET_QUANTITY",
                current.netQuantity(),
                value(previous, EmployeeKpiEntry::netQuantity),
                new EmployeeMetricOptions(
                        COUNT,
                        sufficiency,
                        SECONDARY,
                        true
                )
        );
        if (current.dataQuality().completeCostData()) {
            addMetric(
                    target,
                    employeeRef,
                    "GROSS_PROFIT",
                    current.grossProfit(),
                    value(previous, EmployeeKpiEntry::grossProfit),
                    new EmployeeMetricOptions(
                            MONEY,
                            sufficiency,
                            SECONDARY,
                            true
                    )
            );
            addMetric(
                    target,
                    employeeRef,
                    "MARGIN_PERCENT",
                    current.marginPercent(),
                    value(previous, EmployeeKpiEntry::marginPercent),
                    new EmployeeMetricOptions(
                            PERCENT,
                            sufficiency,
                            SECONDARY,
                            false
                    )
            );
        }
    }

    private void addEfficiency(
            List<Fact> target,
            String employeeRef,
            EmployeeRatingEntry current,
            EmployeeRatingEntry previous,
            Sufficiency workload,
            Sufficiency previousWorkload
    ) {
        if (current == null || workload != Sufficiency.SUFFICIENT) {
            return;
        }
        Sufficiency dynamics = policy.dynamics(workload, previousWorkload);
        addMetric(
                target,
                employeeRef,
                "STORE_REVENUE_SHARE_PERCENT",
                current.storeRevenueSharePercent(),
                rating(previous,
                        EmployeeRatingEntry::storeRevenueSharePercent),
                new EmployeeMetricOptions(
                        PERCENT,
                        workload,
                        SECONDARY,
                        false
                )
        );
        addMetric(
                target,
                employeeRef,
                "REVENUE_PER_SHIFT",
                current.revenuePerShift(),
                rating(previous, EmployeeRatingEntry::revenuePerShift),
                new EmployeeMetricOptions(
                        MONEY,
                        dynamics,
                        SECONDARY,
                        true
                )
        );
        addMetric(
                target,
                employeeRef,
                "REVENUE_PER_HOUR",
                current.revenuePerHour(),
                rating(previous, EmployeeRatingEntry::revenuePerHour),
                new EmployeeMetricOptions(
                        MONEY,
                        dynamics,
                        PRIMARY,
                        true
                )
        );
        addMetric(
                target,
                employeeRef,
                "ADDITIONAL_REVENUE",
                current.additionalRevenue(),
                rating(previous,
                        EmployeeRatingEntry::additionalRevenue),
                new EmployeeMetricOptions(
                        MONEY,
                        dynamics,
                        SECONDARY,
                        true
                )
        );
        addMetric(
                target,
                employeeRef,
                "ADDITIONAL_SHARE_PERCENT",
                current.additionalSharePercent(),
                rating(previous,
                        EmployeeRatingEntry::additionalSharePercent),
                new EmployeeMetricOptions(
                        PERCENT,
                        dynamics,
                        SECONDARY,
                        false
                )
        );
    }

    private void addRating(
            List<Fact> target,
            String employeeRef,
            EmployeeRatingEntry current,
            EmployeeRatingEntry previous,
            Sufficiency sufficiency
    ) {
        if (current == null || current.scores() == null) {
            return;
        }
        RatingScoreBreakdown before = previous == null ? null : previous.scores();
        addRatingMetric(target, employeeRef, "OVERALL_SCORE", current.scores().overallScore(),
                score(before, RatingScoreBreakdown::overallScore), sufficiency, PRIMARY);
        addRatingMetric(target, employeeRef, "COVERAGE_PERCENT", current.scores().coveragePercent(),
                score(before, RatingScoreBreakdown::coveragePercent), sufficiency, CONTEXT);
        addRatingMetric(target, employeeRef, "CONTRIBUTION_SCORE",
                current.scores().contributionScore(),
                score(before, RatingScoreBreakdown::contributionScore), sufficiency, SECONDARY);
        addRatingMetric(target, employeeRef, "EFFICIENCY_SCORE",
                current.scores().efficiencyScore(),
                score(before, RatingScoreBreakdown::efficiencyScore), sufficiency, SECONDARY);
        addRatingMetric(target, employeeRef, "STRUCTURE_SCORE",
                current.scores().structureScore(),
                score(before, RatingScoreBreakdown::structureScore), sufficiency, SECONDARY);
        addRatingMetric(target, employeeRef, "ATTACH_SCORE", current.scores().attachScore(),
                score(before, RatingScoreBreakdown::attachScore), sufficiency, SECONDARY);
        if (current.rank() != null) {
            target.add(SnapshotFactFactory.numeric(
                    "EMP:" + employeeRef + ".RATING.RANK.CURRENT",
                    "RATING_RANK",
                    BigDecimal.valueOf(current.rank()),
                    previous == null || previous.rank() == null
                            ? null : BigDecimal.valueOf(previous.rank()),
                    new SnapshotFactFactory.FactOptions(
                            null,
                            RANK,
                            sufficiency,
                            CONTEXT,
                            false
                    )
            ));
        }
    }

    private void addRatingMetric(
            List<Fact> target,
            String employeeRef,
            String code,
            BigDecimal current,
            BigDecimal previous,
            Sufficiency sufficiency,
            Materiality materiality
    ) {
        target.add(SnapshotFactFactory.numeric(
                "EMP:" + employeeRef + ".RATING." + code + ".CURRENT",
                "RATING_" + code,
                current,
                previous,
                new SnapshotFactFactory.FactOptions(
                        null,
                        code.endsWith("PERCENT") ? PERCENT : SCORE,
                        sufficiency,
                        materiality,
                        false
                )
        ));
    }

    private void addAttach(
            List<Fact> target,
            String employeeRef,
            EmployeeRatingEntry current,
            EmployeeRatingEntry previous
    ) {
        if (current == null) {
            return;
        }
        Map<String, EmployeeAttachRatingEntry> before = previous == null
                ? Map.of()
                : previous.attachRates().stream().collect(Collectors.toMap(
                        EmployeeAttachRatingEntry::metricCode,
                        Function.identity()
                ));
        current.attachRates().stream()
                .sorted(Comparator.comparing(EmployeeAttachRatingEntry::metricCode))
                .forEach(rate -> {
                    EmployeeAttachRatingEntry old = before.get(rate.metricCode());
                    Sufficiency sample = policy.attach(rate.denominatorReceiptCount());
                    String prefix = "EMP:" + employeeRef + ".ATTACH:"
                            + rate.metricCode() + ".";
                    target.add(SnapshotFactFactory.numeric(
                            prefix + "NUMERATOR_RECEIPT_COUNT.CURRENT",
                            "NUMERATOR_RECEIPT_COUNT",
                            rate.numeratorReceiptCount(),
                            old == null ? null : old.numeratorReceiptCount(),
                            new SnapshotFactFactory.FactOptions(
                                    rate.numeratorCategoryCode(),
                                    COUNT,
                                    sample,
                                    CONTEXT,
                                    true
                            )
                    ));
                    target.add(SnapshotFactFactory.numeric(
                            prefix + "DENOMINATOR_RECEIPT_COUNT.CURRENT",
                            "DENOMINATOR_RECEIPT_COUNT",
                            rate.denominatorReceiptCount(),
                            old == null ? null : old.denominatorReceiptCount(),
                            new SnapshotFactFactory.FactOptions(
                                    rate.numeratorCategoryCode(),
                                    COUNT,
                                    sample,
                                    CONTEXT,
                                    true
                            )
                    ));
                    target.add(SnapshotFactFactory.numeric(
                            prefix + "RATE_PER_HUNDRED.CURRENT",
                            "RATE_PER_HUNDRED",
                            rate.ratePercent(),
                            old == null ? null : old.ratePercent(),
                            new SnapshotFactFactory.FactOptions(
                                    rate.numeratorCategoryCode(),
                                    RATE_PER_HUNDRED,
                                    sample,
                                    SECONDARY,
                                    false
                            )
                    ));
                });
    }

    private void addCategories(
            List<Fact> target,
            String employeeRef,
            EmployeeCategoryKpiEmployee current,
            EmployeeCategoryKpiEmployee previous,
            Sufficiency sufficiency
    ) {
        if (current == null) {
            return;
        }
        Map<String, EmployeeCategoryKpiGroup> previousGroups = previous == null
                ? Map.of()
                : previous.groups().stream().collect(Collectors.toMap(
                        EmployeeCategoryKpiGroup::groupCode,
                        Function.identity()
                ));
        current.groups().stream()
                .filter(group -> hasActivity(
                        group.metrics(),
                        groupMetrics(previousGroups.get(group.groupCode()))
                ))
                .sorted(Comparator.comparing(EmployeeCategoryKpiGroup::groupCode))
                .forEach(group -> addCategoryMetrics(
                        target,
                        "EMP:" + employeeRef + ".GROUP:" + group.groupCode() + ".",
                        null,
                        group.metrics(),
                        groupMetrics(previousGroups.get(group.groupCode())),
                        sufficiency
                ));
        Map<String, EmployeeCategoryKpiEntry> previousCategories = previous == null
                ? Map.of()
                : previous.categories().stream().collect(Collectors.toMap(
                        EmployeeCategoryKpiEntry::categoryCode,
                        Function.identity()
                ));
        current.categories().stream()
                .filter(category -> hasActivity(
                        category.metrics(),
                        categoryMetrics(previousCategories.get(category.categoryCode()))
                ))
                .sorted(Comparator.comparing(EmployeeCategoryKpiEntry::categoryCode))
                .forEach(category -> addCategoryMetrics(
                        target,
                        "EMP:" + employeeRef + ".CATEGORY:"
                                + category.categoryCode() + ".",
                        category.categoryCode(),
                        category.metrics(),
                        categoryMetrics(previousCategories.get(category.categoryCode())),
                        sufficiency
                ));
    }

    private boolean hasActivity(
            EmployeeCategoryKpiMetrics current,
            EmployeeCategoryKpiMetrics previous
    ) {
        return current.dataQuality().includedItemCount() > 0
                || previous != null
                && previous.dataQuality().includedItemCount() > 0;
    }

    private void addCategoryMetrics(
            List<Fact> target,
            String prefix,
            String categoryCode,
            EmployeeCategoryKpiMetrics current,
            EmployeeCategoryKpiMetrics previous,
            Sufficiency sufficiency
    ) {
        target.add(SnapshotFactFactory.numeric(
                prefix + "NET_REVENUE.CURRENT",
                "NET_REVENUE",
                current.netRevenue(),
                metric(previous, EmployeeCategoryKpiMetrics::netRevenue),
                new SnapshotFactFactory.FactOptions(
                        categoryCode,
                        MONEY,
                        sufficiency,
                        SECONDARY,
                        true
                )
        ));
        target.add(SnapshotFactFactory.numeric(
                prefix + "NET_QUANTITY.CURRENT",
                "NET_QUANTITY",
                current.netQuantity(),
                metric(previous, EmployeeCategoryKpiMetrics::netQuantity),
                new SnapshotFactFactory.FactOptions(
                        categoryCode,
                        COUNT,
                        sufficiency,
                        CONTEXT,
                        true
                )
        ));
        target.add(SnapshotFactFactory.numeric(
                prefix + "REVENUE_SHARE_PERCENT.CURRENT",
                "REVENUE_SHARE_PERCENT",
                current.revenueSharePercent(),
                metric(previous, EmployeeCategoryKpiMetrics::revenueSharePercent),
                new SnapshotFactFactory.FactOptions(
                        categoryCode,
                        PERCENT,
                        sufficiency,
                        SECONDARY,
                        false
                )
        ));
    }

    private void addMetric(
            List<Fact> target,
            String employeeRef,
            String code,
            BigDecimal current,
            BigDecimal previous,
            EmployeeMetricOptions options
    ) {
        target.add(SnapshotFactFactory.numeric(
                "EMP:" + employeeRef + "." + code + ".CURRENT",
                code,
                current,
                previous,
                new SnapshotFactFactory.FactOptions(
                        null,
                        options.unit(),
                        options.sufficiency(),
                        options.materiality(),
                        options.relativeDelta()
                )
        ));
    }

    private static List<String> availableSections(List<Fact> facts) {
        List<String> result = new ArrayList<>();
        if (facts.stream().anyMatch(fact -> fact.evidenceRef().contains(".WORKLOAD."))) {
            result.add("WORKLOAD");
        }
        if (facts.stream().anyMatch(fact -> fact.evidenceRef().contains(".RATING."))) {
            result.add("RATING");
        }
        if (facts.stream().anyMatch(fact -> fact.evidenceRef().contains(".GROUP:")
                || fact.evidenceRef().contains(".CATEGORY:"))) {
            result.add("CATEGORIES");
        }
        if (facts.stream().anyMatch(fact -> fact.evidenceRef().contains(".ATTACH:"))) {
            result.add("ATTACH");
        }
        if (facts.stream().anyMatch(fact -> fact.metricCode().equals("NET_REVENUE"))) {
            result.add("RESULT");
        }
        return List.copyOf(result);
    }

    private static BigDecimal value(
            EmployeeKpiEntry value,
            Function<EmployeeKpiEntry, BigDecimal> getter
    ) {
        return value == null ? null : getter.apply(value);
    }

    private static BigDecimal rating(
            EmployeeRatingEntry value,
            Function<EmployeeRatingEntry, BigDecimal> getter
    ) {
        return value == null ? null : getter.apply(value);
    }

    private static BigDecimal score(
            RatingScoreBreakdown value,
            Function<RatingScoreBreakdown, BigDecimal> getter
    ) {
        return value == null ? null : getter.apply(value);
    }

    private static EmployeeCategoryKpiMetrics groupMetrics(EmployeeCategoryKpiGroup value) {
        return value == null ? null : value.metrics();
    }

    private static EmployeeCategoryKpiMetrics categoryMetrics(EmployeeCategoryKpiEntry value) {
        return value == null ? null : value.metrics();
    }

    private static BigDecimal metric(
            EmployeeCategoryKpiMetrics value,
            Function<EmployeeCategoryKpiMetrics, BigDecimal> getter
    ) {
        return value == null ? null : getter.apply(value);
    }

    private record EmployeeMetricOptions(
            Unit unit,
            Sufficiency sufficiency,
            Materiality materiality,
            boolean relativeDelta
    ) {
    }

    private record PeriodIndex(
            Map<UUID, EmployeeKpiEntry> kpis,
            Map<UUID, EmployeeCategoryKpiEmployee> categories,
            Map<UUID, EmployeeRatingEntry> ratings,
            EmployeeSalesSampleFacts salesSamples
    ) {

        static PeriodIndex of(WeeklyPeriodFacts facts) {
            return new PeriodIndex(
                    facts.employees().employees().stream()
                            .filter(employee -> !employee.unassigned())
                            .collect(Collectors.toMap(EmployeeKpiEntry::employeeId,
                                    Function.identity())),
                    facts.employeeCategories().employees().stream()
                            .filter(employee -> !employee.unassigned())
                            .collect(Collectors.toMap(EmployeeCategoryKpiEmployee::employeeId,
                                    Function.identity())),
                    facts.employeeRatings().employees().stream()
                            .collect(Collectors.toMap(EmployeeRatingEntry::employeeId,
                                    Function.identity())),
                    facts.employeeSalesSamples()
            );
        }
    }
}
