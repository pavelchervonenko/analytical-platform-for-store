package com.storeanalytics.metrics.service;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.metrics.exception.StoreNotFoundException;
import com.storeanalytics.metrics.repository.EmployeeCategoryKpiAggregate;
import com.storeanalytics.metrics.repository.EmployeeCategoryKpiRepository;
import com.storeanalytics.product.model.AnalyticsCategoryKind;
import com.storeanalytics.store.repository.StoreRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmployeeCategoryKpiService {

    static final String FORMULA_VERSION = "employee-category-kpi-v1";
    private static final String UNASSIGNED_DISPLAY_NAME = "Не назначен";
    private static final int PERCENT_SCALE = 2;

    private final StoreRepository storeRepository;
    private final EmployeeCategoryKpiRepository repository;

    public EmployeeCategoryKpiService(
            StoreRepository storeRepository,
            EmployeeCategoryKpiRepository repository
    ) {
        this.storeRepository = storeRepository;
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public EmployeeCategoryKpiResult calculate(
            UUID storeId,
            StoreKpiPeriod period
    ) {
        UUID validatedStoreId = requireNonNull(storeId, "storeId");
        StoreKpiPeriod validatedPeriod = requireNonNull(period, "period");
        if (!storeRepository.existsById(validatedStoreId)) {
            throw new StoreNotFoundException(validatedStoreId);
        }

        Map<EmployeeKey, List<EmployeeCategoryKpiAggregate>> rowsByEmployee =
                repository.aggregate(
                        validatedStoreId,
                        validatedPeriod.start(),
                        validatedPeriod.end()
                ).stream().collect(Collectors.groupingBy(
                        row -> new EmployeeKey(row.employeeId(), row.unassigned()),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        List<EmployeeCategoryKpiEmployee> employees = rowsByEmployee.values().stream()
                .map(this::toEmployee)
                .toList();
        return new EmployeeCategoryKpiResult(
                validatedStoreId,
                validatedPeriod.start(),
                validatedPeriod.end(),
                FORMULA_VERSION,
                CategoryKpiService.FORMULA_VERSION,
                employees
        );
    }

    private EmployeeCategoryKpiEmployee toEmployee(
            List<EmployeeCategoryKpiAggregate> rows
    ) {
        EmployeeCategoryKpiAggregate identity = rows.getFirst();
        CategoryKpiMetrics overall = CategoryKpiMetricsCalculator.calculate(rows);
        BigDecimal employeeRevenue = overall.netRevenue();
        List<EmployeeCategoryKpiEntry> categories = rows.stream()
                .map(row -> toCategory(row, employeeRevenue))
                .toList();
        List<EmployeeCategoryKpiGroup> groups = List.of(
                group("PHONES", "Телефоны", rows,
                        EmployeeCategoryKpiAggregate::countsAsPhone, employeeRevenue),
                group("DEVICES", "Устройства", rows,
                        EmployeeCategoryKpiAggregate::countsAsDevice, employeeRevenue),
                group("ACCESSORY", "Аксессуары", rows,
                        row -> row.categoryKind() == AnalyticsCategoryKind.ACCESSORY,
                        employeeRevenue),
                group("SERVICE", "Услуги", rows,
                        this::isServiceCategory, employeeRevenue),
                group("ADDITIONAL_REVENUE", "Дополнительная выручка", rows,
                        EmployeeCategoryKpiAggregate::countsAsAdditionalRevenue,
                        employeeRevenue)
        );
        long unmappedItemCount = rows.stream()
                .filter(row -> "UNMAPPED".equals(row.categoryCode()))
                .mapToLong(EmployeeCategoryKpiAggregate::includedItemCount)
                .sum();
        boolean rankingEligible = !identity.unassigned()
                && identity.employeeActive()
                && identity.assignmentActive()
                && identity.participatesInRanking();

        return new EmployeeCategoryKpiEmployee(
                identity.employeeId(),
                identity.unassigned()
                        ? UNASSIGNED_DISPLAY_NAME
                        : identity.displayName(),
                identity.employeeActive(),
                identity.assignedToStore(),
                identity.assignmentActive(),
                identity.participatesInRanking(),
                rankingEligible,
                identity.unassigned(),
                employeeRevenue,
                new EmployeeKpiDataQuality(
                        overall.dataQuality().completeCostData(),
                        overall.dataQuality().includedItemCount(),
                        unmappedItemCount,
                        overall.dataQuality().missingCostItemCount(),
                        overall.dataQuality().unexpectedZeroCostItemCount()
                ),
                groups,
                categories
        );
    }

    private EmployeeCategoryKpiEntry toCategory(
            EmployeeCategoryKpiAggregate row,
            BigDecimal employeeRevenue
    ) {
        return new EmployeeCategoryKpiEntry(
                row.categoryCode(),
                row.categoryName(),
                row.categoryKind(),
                row.deviceFamily(),
                row.categoryActive(),
                row.countsAsPhone(),
                row.countsAsDevice(),
                row.countsAsAdditionalRevenue(),
                metrics(List.of(row), employeeRevenue)
        );
    }

    private EmployeeCategoryKpiGroup group(
            String code,
            String name,
            List<EmployeeCategoryKpiAggregate> rows,
            Predicate<EmployeeCategoryKpiAggregate> membership,
            BigDecimal employeeRevenue
    ) {
        List<EmployeeCategoryKpiAggregate> members = rows.stream()
                .filter(membership)
                .toList();
        return new EmployeeCategoryKpiGroup(
                code,
                name,
                metrics(members, employeeRevenue)
        );
    }

    private EmployeeCategoryKpiMetrics metrics(
            List<EmployeeCategoryKpiAggregate> rows,
            BigDecimal employeeRevenue
    ) {
        CategoryKpiMetrics base = CategoryKpiMetricsCalculator.calculate(rows);
        BigDecimal share = employeeRevenue.signum() == 0
                ? null
                : base.netRevenue()
                        .multiply(BigDecimal.valueOf(100))
                        .divide(employeeRevenue, PERCENT_SCALE, RoundingMode.HALF_UP);
        return new EmployeeCategoryKpiMetrics(
                base.netRevenue(),
                base.netQuantity(),
                base.costAmount(),
                base.grossProfit(),
                base.marginPercent(),
                share,
                base.dataQuality()
        );
    }

    private boolean isServiceCategory(EmployeeCategoryKpiAggregate row) {
        return switch (row.categoryKind()) {
            case SERVICE, WARRANTY, PROTECTION -> true;
            default -> false;
        };
    }

    private record EmployeeKey(UUID employeeId, boolean unassigned) {
    }
}
