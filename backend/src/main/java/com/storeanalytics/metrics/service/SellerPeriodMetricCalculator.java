package com.storeanalytics.metrics.service;

import com.storeanalytics.metrics.repository.CategoryKpiAggregate;
import com.storeanalytics.metrics.repository.CategoryMetricValues;
import com.storeanalytics.metrics.repository.EmployeeCategoryKpiAggregate;
import com.storeanalytics.metrics.repository.EmployeeKpiAggregate;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

final class SellerPeriodMetricCalculator {

    private SellerPeriodMetricCalculator() {
    }

    static SellerPeriodMetrics calculate(
            SellerCohortSnapshot cohort,
            StoreKpiPeriod period,
            List<EmployeeKpiAggregate> allEmployees,
            List<EmployeeCategoryKpiAggregate> allCategories
    ) {
        Set<UUID> ids = Set.copyOf(cohort.employeeIds());
        List<EmployeeKpiAggregate> employees = allEmployees.stream()
                .filter(row -> row.employeeId() != null && ids.contains(row.employeeId()))
                .toList();
        List<EmployeeCategoryKpiAggregate> categories = allCategories.stream()
                .filter(row -> row.employeeId() != null && ids.contains(row.employeeId()))
                .toList();
        if (employees.size() != ids.size()
                || !employees.stream().map(EmployeeKpiAggregate::employeeId)
                        .collect(Collectors.toSet()).equals(ids)
                || !categories.stream().map(EmployeeCategoryKpiAggregate::employeeId)
                        .collect(Collectors.toSet()).equals(ids)) {
            throw new IllegalStateException("Seller projections do not cover the selected cohort");
        }
        CategoryKpiMetrics totals = CategoryKpiMetricsCalculator.calculate(employees);
        if (!totals.equals(CategoryKpiMetricsCalculator.calculate(categories))) {
            throw new IllegalStateException("Seller employee/category reconciliation failed");
        }
        long unmapped = employees.stream().mapToLong(EmployeeKpiAggregate::unmappedItemCount).sum();
        long categoryUnmapped = categories.stream().filter(row -> "UNMAPPED".equals(row.categoryCode()))
                .mapToLong(EmployeeCategoryKpiAggregate::includedItemCount).sum();
        if (unmapped != categoryUnmapped) {
            throw new IllegalStateException("Seller unmapped reconciliation failed");
        }
        Map<String, List<EmployeeCategoryKpiAggregate>> grouped = categories.stream()
                .collect(Collectors.groupingBy(EmployeeCategoryKpiAggregate::categoryCode,
                        LinkedHashMap::new, Collectors.toList()));
        CategoryKpiResult projection = CategoryKpiService.project(cohort.storeId(), period,
                grouped.values().stream().map(SellerPeriodMetricCalculator::combine).toList());
        reconcileAdditional(projection.groups());
        return new SellerPeriodMetrics(cohort, period, totals, unmapped, projection, employees, categories);
    }

    private static CategoryKpiAggregate combine(List<EmployeeCategoryKpiAggregate> rows) {
        EmployeeCategoryKpiAggregate first = rows.getFirst();
        return new CategoryKpiAggregate(
                first.categoryCode(), first.categoryName(), first.categoryKind(), first.deviceFamily(),
                first.categoryActive(), first.countsAsPhone(), first.countsAsDevice(),
                first.countsAsAdditionalRevenue(),
                sum(rows, CategoryMetricValues::netRevenue), sum(rows, CategoryMetricValues::netQuantity),
                sum(rows, CategoryMetricValues::costAmount),
                rows.stream().mapToLong(CategoryMetricValues::includedItemCount).sum(),
                rows.stream().mapToLong(CategoryMetricValues::missingCostItemCount).sum(),
                rows.stream().mapToLong(CategoryMetricValues::unexpectedZeroCostItemCount).sum()
        );
    }

    private static void reconcileAdditional(List<CategoryKpiGroup> groups) {
        Map<String, CategoryKpiMetrics> byCode = groups.stream().collect(
                Collectors.toMap(CategoryKpiGroup::groupCode, CategoryKpiGroup::metrics));
        CategoryKpiMetrics accessory = byCode.get("ACCESSORY");
        CategoryKpiMetrics service = byCode.get("SERVICE");
        CategoryKpiMetrics additional = byCode.get("ADDITIONAL_REVENUE");
        if (accessory.netRevenue().add(service.netRevenue()).compareTo(additional.netRevenue()) != 0
                || accessory.netQuantity().add(service.netQuantity()).compareTo(additional.netQuantity()) != 0) {
            throw new IllegalStateException("Seller additional reconciliation failed");
        }
    }

    private static BigDecimal sum(
            Collection<? extends CategoryMetricValues> rows,
            java.util.function.Function<CategoryMetricValues, BigDecimal> value
    ) {
        return rows.stream().map(value).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
