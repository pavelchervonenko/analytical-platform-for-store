package com.storeanalytics.metrics.service;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OverviewMetricsService {

    public static final String FORMULA_VERSION = "overview-metrics-v1";
    private static final int MONEY_SCALE = 2;
    private static final int QUANTITY_SCALE = 3;
    private static final int PERCENT_SCALE = 2;

    private final StoreKpiService storeKpiService;
    private final CategoryKpiService categoryKpiService;
    private final EmployeeKpiService employeeKpiService;
    private final EmployeeCategoryKpiService employeeCategoryKpiService;

    public OverviewMetricsService(
            StoreKpiService storeKpiService,
            CategoryKpiService categoryKpiService,
            EmployeeKpiService employeeKpiService,
            EmployeeCategoryKpiService employeeCategoryKpiService
    ) {
        this.storeKpiService = storeKpiService;
        this.categoryKpiService = categoryKpiService;
        this.employeeKpiService = employeeKpiService;
        this.employeeCategoryKpiService = employeeCategoryKpiService;
    }

    @Transactional(readOnly = true)
    public OverviewMetricsResult calculate(
            UUID storeId,
            StoreKpiPeriod period,
            OverviewMetricScope scope
    ) {
        UUID validatedStoreId = requireNonNull(storeId, "storeId");
        StoreKpiPeriod validatedPeriod = requireNonNull(period, "period");
        OverviewMetricScope validatedScope = requireNonNull(scope, "scope");

        StoreKpiResult store = storeKpiService.calculate(validatedStoreId, validatedPeriod);
        CategoryKpiResult storeCategories = categoryKpiService.calculate(
                validatedStoreId, validatedPeriod
        );
        EmployeeKpiResult employees = employeeKpiService.calculate(
                validatedStoreId, validatedPeriod
        );
        EmployeeCategoryKpiResult employeeCategories = employeeCategoryKpiService.calculate(
                validatedStoreId, validatedPeriod
        );

        Aggregate fullEmployee = aggregateEmployees(employees.employees());
        Aggregate sellerEmployee = aggregateEmployees(employees.employees().stream()
                .filter(EmployeeKpiEntry::rankingEligible)
                .toList());
        Aggregate storeAggregate = aggregateStore(store);
        reconcile("full employee KPI", storeAggregate, fullEmployee);

        CommercialGroups storeGroups = storeGroups(storeCategories);
        CommercialGroups fullEmployeeGroups = employeeGroups(employeeCategories.employees());
        CommercialGroups sellerGroups = employeeGroups(employeeCategories.employees().stream()
                .filter(EmployeeCategoryKpiEmployee::rankingEligible)
                .toList());
        List<CategoryKpiGroup> sellerSalesGroups = aggregateEmployeeSalesGroups(
                storeCategories.groups(),
                employeeCategories.employees().stream()
                        .filter(EmployeeCategoryKpiEmployee::rankingEligible)
                        .toList()
        );
        reconcileGroups("full employee category KPI", storeGroups, fullEmployeeGroups);
        reconcileAdditional("store", storeGroups);
        reconcileAdditional("sellers", sellerGroups);
        equal("seller revenue across employee projections",
                sellerEmployee.netRevenue(), sellerGroups.netRevenue());

        Aggregate selected = validatedScope == OverviewMetricScope.STORE
                ? storeAggregate : sellerEmployee;
        CommercialGroups selectedGroups = validatedScope == OverviewMetricScope.STORE
                ? storeGroups : sellerGroups;
        List<CategoryKpiGroup> selectedSalesGroups =
                validatedScope == OverviewMetricScope.STORE
                        ? storeCategories.groups() : sellerSalesGroups;
        return result(
                validatedStoreId,
                validatedPeriod,
                validatedScope,
                selected,
                selectedGroups,
                selectedSalesGroups,
                store.dataQuality()
        );
    }

    private OverviewMetricsResult result(
            UUID storeId,
            StoreKpiPeriod period,
            OverviewMetricScope scope,
            Aggregate aggregate,
            CommercialGroups groups,
            List<CategoryKpiGroup> salesGroups,
            StoreKpiDataQuality storeDataQuality
    ) {
        BigDecimal revenue = money(aggregate.netRevenue());
        BigDecimal cost = aggregate.completeCostData() ? money(aggregate.costAmount()) : null;
        BigDecimal grossProfit = cost == null ? null : money(revenue.subtract(cost));
        BigDecimal margin = grossProfit == null || revenue.signum() == 0
                ? null
                : grossProfit.multiply(BigDecimal.valueOf(100))
                        .divide(revenue, PERCENT_SCALE, RoundingMode.HALF_UP);
        return new OverviewMetricsResult(
                storeId,
                period.start(),
                period.end(),
                scope,
                FORMULA_VERSION,
                revenue,
                quantity(aggregate.netQuantity()),
                cost,
                grossProfit,
                margin,
                commercial(groups.additional(), revenue),
                commercial(groups.accessory(), revenue),
                commercial(groups.service(), revenue),
                salesGroups,
                new OverviewMetricsDataQuality(
                        aggregate.completeCostData(),
                        aggregate.includedItemCount(),
                        aggregate.unmappedItemCount(),
                        aggregate.missingCostItemCount(),
                        aggregate.unexpectedZeroCostItemCount(),
                        storeDataQuality.periodOpenConsistencyIssueCount(),
                        storeDataQuality.storeOpenQualityIssueCount(),
                        true
                )
        );
    }

    private List<CategoryKpiGroup> aggregateEmployeeSalesGroups(
            List<CategoryKpiGroup> referenceGroups,
            Collection<EmployeeCategoryKpiEmployee> employees
    ) {
        return referenceGroups.stream()
                .map(reference -> new CategoryKpiGroup(
                        reference.groupCode(),
                        reference.groupName(),
                        aggregateEmployeeGroupMetrics(employees.stream()
                                .map(employee -> employeeGroup(
                                        employee.groups(), reference.groupCode()
                                ).metrics())
                                .toList())
                ))
                .toList();
    }

    private CategoryKpiMetrics aggregateEmployeeGroupMetrics(
            List<EmployeeCategoryKpiMetrics> metrics
    ) {
        BigDecimal revenue = money(sum(
                metrics, EmployeeCategoryKpiMetrics::netRevenue
        ));
        BigDecimal netQuantity = quantity(sum(
                metrics, EmployeeCategoryKpiMetrics::netQuantity
        ));
        boolean completeCostData = metrics.stream()
                .allMatch(metric -> metric.dataQuality().completeCostData());
        BigDecimal cost = completeCostData
                ? money(sum(metrics, metric -> Objects.requireNonNull(metric.costAmount())))
                : null;
        BigDecimal grossProfit = cost == null
                ? null : money(revenue.subtract(cost));
        BigDecimal averageGrossProfitPerUnit = grossProfit == null
                || netQuantity.signum() <= 0
                ? null
                : grossProfit.divide(netQuantity, MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal marginPercent = grossProfit == null || revenue.signum() == 0
                ? null
                : grossProfit.multiply(BigDecimal.valueOf(100))
                        .divide(revenue, PERCENT_SCALE, RoundingMode.HALF_UP);
        return new CategoryKpiMetrics(
                revenue,
                netQuantity,
                cost,
                grossProfit,
                averageGrossProfitPerUnit,
                marginPercent,
                new CategoryKpiDataQuality(
                        completeCostData,
                        metrics.stream().mapToLong(
                                metric -> metric.dataQuality().includedItemCount()
                        ).sum(),
                        metrics.stream().mapToLong(
                                metric -> metric.dataQuality().missingCostItemCount()
                        ).sum(),
                        metrics.stream().mapToLong(
                                metric -> metric.dataQuality().unexpectedZeroCostItemCount()
                        ).sum()
                )
        );
    }

    private OverviewCommercialMetric commercial(GroupAmount group, BigDecimal revenue) {
        BigDecimal amount = money(group.netRevenue());
        BigDecimal share = revenue.signum() <= 0
                ? null
                : amount.multiply(BigDecimal.valueOf(100))
                        .divide(revenue, PERCENT_SCALE, RoundingMode.HALF_UP);
        return new OverviewCommercialMetric(
                amount,
                quantity(group.netQuantity()),
                share
        );
    }

    private Aggregate aggregateStore(StoreKpiResult store) {
        return new Aggregate(
                store.netRevenue(),
                store.netQuantity(),
                Objects.requireNonNullElse(store.costAmount(), BigDecimal.ZERO),
                store.dataQuality().completeCostData(),
                store.dataQuality().includedItemCount(),
                store.dataQuality().unmappedItemCount(),
                store.dataQuality().missingCostItemCount(),
                store.dataQuality().unexpectedZeroCostItemCount()
        );
    }

    private Aggregate aggregateEmployees(Collection<EmployeeKpiEntry> employees) {
        boolean completeCostData = employees.stream()
                .allMatch(entry -> entry.dataQuality().completeCostData());
        return new Aggregate(
                sum(employees, EmployeeKpiEntry::netRevenue),
                sum(employees, EmployeeKpiEntry::netQuantity),
                completeCostData
                        ? sum(employees, entry -> Objects.requireNonNull(entry.costAmount()))
                        : BigDecimal.ZERO,
                completeCostData,
                employees.stream().mapToLong(
                        entry -> entry.dataQuality().includedItemCount()
                ).sum(),
                employees.stream().mapToLong(
                        entry -> entry.dataQuality().unmappedItemCount()
                ).sum(),
                employees.stream().mapToLong(
                        entry -> entry.dataQuality().missingCostItemCount()
                ).sum(),
                employees.stream().mapToLong(
                        entry -> entry.dataQuality().unexpectedZeroCostItemCount()
                ).sum()
        );
    }

    private CommercialGroups storeGroups(CategoryKpiResult result) {
        return new CommercialGroups(
                group(result.groups(), "ACCESSORY"),
                group(result.groups(), "SERVICE"),
                group(result.groups(), "ADDITIONAL_REVENUE"),
                result.categories().stream()
                        .map(CategoryKpiEntry::metrics)
                        .map(CategoryKpiMetrics::netRevenue)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
        );
    }

    private CommercialGroups employeeGroups(
            Collection<EmployeeCategoryKpiEmployee> employees
    ) {
        GroupAmount accessory = sumEmployeeGroup(employees, "ACCESSORY");
        GroupAmount service = sumEmployeeGroup(employees, "SERVICE");
        GroupAmount additional = sumEmployeeGroup(employees, "ADDITIONAL_REVENUE");
        BigDecimal revenue = employees.stream()
                .map(EmployeeCategoryKpiEmployee::netRevenue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CommercialGroups(accessory, service, additional, revenue);
    }

    private GroupAmount sumEmployeeGroup(
            Collection<EmployeeCategoryKpiEmployee> employees,
            String code
    ) {
        List<EmployeeCategoryKpiMetrics> metrics = employees.stream()
                .map(employee -> employeeGroup(employee.groups(), code).metrics())
                .toList();
        return new GroupAmount(
                metrics.stream().map(EmployeeCategoryKpiMetrics::netRevenue)
                        .reduce(BigDecimal.ZERO, BigDecimal::add),
                metrics.stream().map(EmployeeCategoryKpiMetrics::netQuantity)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
        );
    }

    private GroupAmount group(List<CategoryKpiGroup> groups, String code) {
        CategoryKpiMetrics metrics = groups.stream()
                .filter(group -> code.equals(group.groupCode()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Missing category KPI group " + code
                )).metrics();
        return new GroupAmount(metrics.netRevenue(), metrics.netQuantity());
    }

    private EmployeeCategoryKpiGroup employeeGroup(
            List<EmployeeCategoryKpiGroup> groups,
            String code
    ) {
        return groups.stream()
                .filter(group -> code.equals(group.groupCode()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Missing employee category KPI group " + code
                ));
    }

    private void reconcile(String label, Aggregate expected, Aggregate actual) {
        equal(label + " revenue", expected.netRevenue(), actual.netRevenue());
        equal(label + " quantity", expected.netQuantity(), actual.netQuantity());
        if (expected.completeCostData() && actual.completeCostData()) {
            equal(label + " cost", expected.costAmount(), actual.costAmount());
        }
        if (expected.includedItemCount() != actual.includedItemCount()
                || expected.unmappedItemCount() != actual.unmappedItemCount()
                || expected.missingCostItemCount() != actual.missingCostItemCount()
                || expected.unexpectedZeroCostItemCount()
                != actual.unexpectedZeroCostItemCount()) {
            throw new IllegalStateException(label + " data-quality reconciliation failed");
        }
    }

    private void reconcileGroups(
            String label,
            CommercialGroups expected,
            CommercialGroups actual
    ) {
        equal(label + " revenue", expected.netRevenue(), actual.netRevenue());
        equal(label + " accessory revenue",
                expected.accessory().netRevenue(), actual.accessory().netRevenue());
        equal(label + " accessory quantity",
                expected.accessory().netQuantity(), actual.accessory().netQuantity());
        equal(label + " service revenue",
                expected.service().netRevenue(), actual.service().netRevenue());
        equal(label + " service quantity",
                expected.service().netQuantity(), actual.service().netQuantity());
        equal(label + " additional revenue",
                expected.additional().netRevenue(), actual.additional().netRevenue());
        equal(label + " additional quantity",
                expected.additional().netQuantity(), actual.additional().netQuantity());
    }

    private void reconcileAdditional(String label, CommercialGroups groups) {
        equal(label + " additional revenue",
                groups.accessory().netRevenue().add(groups.service().netRevenue()),
                groups.additional().netRevenue());
        equal(label + " additional quantity",
                groups.accessory().netQuantity().add(groups.service().netQuantity()),
                groups.additional().netQuantity());
    }

    private void equal(String label, BigDecimal expected, BigDecimal actual) {
        if (expected.compareTo(actual) != 0) {
            throw new IllegalStateException(label + " reconciliation failed");
        }
    }

    private <T> BigDecimal sum(
            Collection<T> values,
            java.util.function.Function<T, BigDecimal> mapper
    ) {
        return values.stream().map(mapper).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
    }

    private BigDecimal quantity(BigDecimal value) {
        return value.setScale(QUANTITY_SCALE, RoundingMode.UNNECESSARY);
    }

    private record Aggregate(
            BigDecimal netRevenue,
            BigDecimal netQuantity,
            BigDecimal costAmount,
            boolean completeCostData,
            long includedItemCount,
            long unmappedItemCount,
            long missingCostItemCount,
            long unexpectedZeroCostItemCount
    ) {
    }

    private record GroupAmount(BigDecimal netRevenue, BigDecimal netQuantity) {
    }

    private record CommercialGroups(
            GroupAmount accessory,
            GroupAmount service,
            GroupAmount additional,
            BigDecimal netRevenue
    ) {
    }
}
