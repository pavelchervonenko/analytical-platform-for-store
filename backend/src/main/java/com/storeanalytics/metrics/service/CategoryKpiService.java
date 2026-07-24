package com.storeanalytics.metrics.service;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.metrics.exception.StoreNotFoundException;
import com.storeanalytics.metrics.repository.CategoryKpiAggregate;
import com.storeanalytics.metrics.repository.CategoryKpiRepository;
import com.storeanalytics.store.repository.StoreRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CategoryKpiService {

    static final String FORMULA_VERSION = "category-kpi-v1";
    private static final int MONEY_SCALE = 2;
    private static final int QUANTITY_SCALE = 3;
    private static final int PERCENT_SCALE = 2;

    private final StoreRepository storeRepository;
    private final CategoryKpiRepository categoryKpiRepository;

    public CategoryKpiService(
            StoreRepository storeRepository,
            CategoryKpiRepository categoryKpiRepository
    ) {
        this.storeRepository = storeRepository;
        this.categoryKpiRepository = categoryKpiRepository;
    }

    @Transactional(readOnly = true)
    public CategoryKpiResult calculate(UUID storeId, StoreKpiPeriod period) {
        UUID validatedStoreId = requireNonNull(storeId, "storeId");
        StoreKpiPeriod validatedPeriod = requireNonNull(period, "period");
        if (!storeRepository.existsById(validatedStoreId)) {
            throw new StoreNotFoundException(validatedStoreId);
        }

        List<CategoryKpiAggregate> aggregates = categoryKpiRepository.aggregate(
                validatedStoreId,
                validatedPeriod.start(),
                validatedPeriod.end()
        );
        List<CategoryKpiEntry> categories = aggregates.stream()
                .map(this::toEntry)
                .toList();
        List<CategoryKpiGroup> groups = List.of(
                group(
                        "PHONES",
                        "Телефоны",
                        aggregates,
                        CategoryKpiAggregate::countsAsPhone
                ),
                group(
                        "DEVICES",
                        "Устройства",
                        aggregates,
                        CategoryKpiAggregate::countsAsDevice
                ),
                group(
                        "ADDITIONAL_REVENUE",
                        "Дополнительная выручка",
                        aggregates,
                        CategoryKpiAggregate::countsAsAdditionalRevenue
                )
        );
        return new CategoryKpiResult(
                validatedStoreId,
                validatedPeriod.start(),
                validatedPeriod.end(),
                FORMULA_VERSION,
                groups,
                categories
        );
    }

    private CategoryKpiEntry toEntry(CategoryKpiAggregate aggregate) {
        return new CategoryKpiEntry(
                aggregate.categoryCode(),
                aggregate.categoryName(),
                aggregate.categoryKind(),
                aggregate.deviceFamily(),
                aggregate.categoryActive(),
                aggregate.countsAsPhone(),
                aggregate.countsAsDevice(),
                aggregate.countsAsAdditionalRevenue(),
                metrics(List.of(aggregate))
        );
    }

    private CategoryKpiGroup group(
            String code,
            String name,
            List<CategoryKpiAggregate> aggregates,
            Predicate<CategoryKpiAggregate> membership
    ) {
        List<CategoryKpiAggregate> members = aggregates.stream()
                .filter(membership)
                .toList();
        return new CategoryKpiGroup(code, name, metrics(members));
    }

    private CategoryKpiMetrics metrics(List<CategoryKpiAggregate> aggregates) {
        BigDecimal netRevenue = aggregates.stream()
                .map(CategoryKpiAggregate::netRevenue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal netQuantity = aggregates.stream()
                .map(CategoryKpiAggregate::netQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal costAmountValue = aggregates.stream()
                .map(CategoryKpiAggregate::costAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long includedItemCount = aggregates.stream()
                .mapToLong(CategoryKpiAggregate::includedItemCount)
                .sum();
        long missingCostItemCount = aggregates.stream()
                .mapToLong(CategoryKpiAggregate::missingCostItemCount)
                .sum();
        long unexpectedZeroCostItemCount = aggregates.stream()
                .mapToLong(CategoryKpiAggregate::unexpectedZeroCostItemCount)
                .sum();
        boolean completeCostData = missingCostItemCount == 0;

        BigDecimal scaledRevenue = money(netRevenue);
        BigDecimal scaledQuantity = quantity(netQuantity);
        BigDecimal costAmount = completeCostData ? money(costAmountValue) : null;
        BigDecimal grossProfit = completeCostData
                ? money(scaledRevenue.subtract(costAmount))
                : null;
        BigDecimal marginPercent = grossProfit == null || scaledRevenue.signum() == 0
                ? null
                : grossProfit.multiply(BigDecimal.valueOf(100))
                        .divide(scaledRevenue, PERCENT_SCALE, RoundingMode.HALF_UP);
        return new CategoryKpiMetrics(
                scaledRevenue,
                scaledQuantity,
                costAmount,
                grossProfit,
                marginPercent,
                new CategoryKpiDataQuality(
                        completeCostData,
                        includedItemCount,
                        missingCostItemCount,
                        unexpectedZeroCostItemCount
                )
        );
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
    }

    private BigDecimal quantity(BigDecimal value) {
        return value.setScale(QUANTITY_SCALE, RoundingMode.UNNECESSARY);
    }
}
