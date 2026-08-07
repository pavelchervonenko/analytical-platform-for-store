package com.storeanalytics.notification.daily;

import com.storeanalytics.metrics.service.AverageKpiResult;
import com.storeanalytics.metrics.service.AverageKpiService;
import com.storeanalytics.metrics.service.CategoryKpiEntry;
import com.storeanalytics.metrics.service.CategoryKpiResult;
import com.storeanalytics.metrics.service.CategoryKpiService;
import com.storeanalytics.metrics.service.EmployeeKpiEntry;
import com.storeanalytics.metrics.service.EmployeeKpiResult;
import com.storeanalytics.metrics.service.EmployeeKpiService;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import com.storeanalytics.metrics.service.StoreKpiResult;
import com.storeanalytics.metrics.service.StoreKpiService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class DailyStorePulseProjection {

    private static final int PERCENT_SCALE = 1;

    private final StoreKpiService storeKpiService;
    private final CategoryKpiService categoryKpiService;
    private final AverageKpiService averageKpiService;
    private final EmployeeKpiService employeeKpiService;

    public DailyStorePulseProjection(
            StoreKpiService storeKpiService,
            CategoryKpiService categoryKpiService,
            AverageKpiService averageKpiService,
            EmployeeKpiService employeeKpiService
    ) {
        this.storeKpiService = storeKpiService;
        this.categoryKpiService = categoryKpiService;
        this.averageKpiService = averageKpiService;
        this.employeeKpiService = employeeKpiService;
    }

    public DailyStorePulsePayload build(UUID storeId, LocalDate businessDate) {
        LocalDate previousDate = businessDate.minusDays(1);
        StoreKpiPeriod currentPeriod = new StoreKpiPeriod(businessDate, businessDate);
        StoreKpiPeriod previousPeriod = new StoreKpiPeriod(previousDate, previousDate);
        StoreKpiResult current = storeKpiService.calculate(storeId, currentPeriod);
        StoreKpiResult previous = storeKpiService.calculate(storeId, previousPeriod);
        CategoryKpiResult categories = categoryKpiService.calculate(storeId, currentPeriod);
        CategoryKpiResult previousCategories = categoryKpiService.calculate(
                storeId, previousPeriod
        );
        AverageKpiResult averages = averageKpiService.calculate(storeId, currentPeriod);
        EmployeeKpiResult employees = employeeKpiService.calculate(storeId, currentPeriod);
        EmployeeKpiResult previousEmployees = employeeKpiService.calculate(
                storeId, previousPeriod
        );

        return new DailyStorePulsePayload(
                1,
                businessDate,
                previousDate,
                metric(current.netRevenue(), previous.netRevenue()),
                new DailyStorePulsePayload.Metric(
                        averages.averageReceipt().current().value(),
                        averages.averageReceipt().changePercent()
                ),
                additionalRevenue(categories, previousCategories),
                new DailyStorePulsePayload.Metric(
                        averages.additionalRevenuePerPhone().current().value(),
                        averages.additionalRevenuePerPhone().changePercent()
                ),
                categoryLeaders(categories, previousCategories),
                employeeLeaders(employees, previousEmployees),
                new DailyStorePulsePayload.Quality(
                        current.dataQuality().completeCostData(),
                        current.dataQuality().storeOpenQualityIssueCount()
                )
        );
    }

    private DailyStorePulsePayload.Metric additionalRevenue(
            CategoryKpiResult current,
            CategoryKpiResult previous
    ) {
        BigDecimal currentValue = current.groups().stream()
                .filter(group -> "ADDITIONAL_REVENUE".equals(group.groupCode()))
                .findFirst().map(group -> group.metrics().netRevenue())
                .orElse(BigDecimal.ZERO.setScale(2));
        BigDecimal previousValue = previous.groups().stream()
                .filter(group -> "ADDITIONAL_REVENUE".equals(group.groupCode()))
                .findFirst().map(group -> group.metrics().netRevenue())
                .orElse(BigDecimal.ZERO.setScale(2));
        return metric(currentValue, previousValue);
    }

    private java.util.List<DailyStorePulsePayload.NamedMetric> categoryLeaders(
            CategoryKpiResult current,
            CategoryKpiResult previous
    ) {
        Map<String, CategoryKpiEntry> previousByCode = previous.categories().stream()
                .collect(Collectors.toMap(CategoryKpiEntry::categoryCode, Function.identity()));
        return current.categories().stream()
                .filter(CategoryKpiEntry::categoryActive)
                .filter(entry -> entry.metrics().netRevenue().signum() > 0)
                .sorted(Comparator.comparing(
                        (CategoryKpiEntry entry) -> entry.metrics().netRevenue()
                ).reversed())
                .limit(3)
                .map(entry -> {
                    CategoryKpiEntry prior = previousByCode.get(entry.categoryCode());
                    BigDecimal previousValue = prior == null
                            ? BigDecimal.ZERO.setScale(2)
                            : prior.metrics().netRevenue();
                    return named(
                            entry.categoryCode(),
                            entry.categoryName(),
                            entry.metrics().netRevenue(),
                            previousValue
                    );
                })
                .toList();
    }

    private java.util.List<DailyStorePulsePayload.NamedMetric> employeeLeaders(
            EmployeeKpiResult current,
            EmployeeKpiResult previous
    ) {
        Map<UUID, EmployeeKpiEntry> previousById = previous.employees().stream()
                .filter(entry -> entry.employeeId() != null)
                .collect(Collectors.toMap(EmployeeKpiEntry::employeeId, Function.identity()));
        return current.employees().stream()
                .filter(EmployeeKpiEntry::rankingEligible)
                .filter(entry -> entry.netRevenue().signum() != 0)
                .sorted(Comparator.comparing(EmployeeKpiEntry::netRevenue).reversed())
                .limit(3)
                .map(entry -> {
                    EmployeeKpiEntry prior = previousById.get(entry.employeeId());
                    BigDecimal previousValue = prior == null
                            ? BigDecimal.ZERO.setScale(2) : prior.netRevenue();
                    return named(
                            entry.employeeId().toString(),
                            entry.displayName(),
                            entry.netRevenue(),
                            previousValue
                    );
                })
                .toList();
    }

    private DailyStorePulsePayload.NamedMetric named(
            String code,
            String name,
            BigDecimal current,
            BigDecimal previous
    ) {
        return new DailyStorePulsePayload.NamedMetric(
                code,
                name,
                current,
                change(current, previous)
        );
    }

    private DailyStorePulsePayload.Metric metric(
            BigDecimal current,
            BigDecimal previous
    ) {
        return new DailyStorePulsePayload.Metric(current, change(current, previous));
    }

    private BigDecimal change(BigDecimal current, BigDecimal previous) {
        if (current == null || previous == null || previous.signum() == 0) {
            return null;
        }
        return current.subtract(previous)
                .multiply(BigDecimal.valueOf(100))
                .divide(previous.abs(), PERCENT_SCALE, RoundingMode.HALF_UP);
    }
}
