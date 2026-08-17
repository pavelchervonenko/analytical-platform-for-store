package com.storeanalytics.metrics.service;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.metrics.exception.StoreNotFoundException;
import com.storeanalytics.metrics.repository.CategoryKpiAggregate;
import com.storeanalytics.metrics.repository.CategoryKpiRepository;
import com.storeanalytics.store.repository.StoreRepository;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CategoryKpiService {

    static final String FORMULA_VERSION = "category-kpi-v2";

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
        return CategoryKpiMetricsCalculator.calculate(aggregates);
    }
}
