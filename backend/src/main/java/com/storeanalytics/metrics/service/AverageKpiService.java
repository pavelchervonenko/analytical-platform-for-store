package com.storeanalytics.metrics.service;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.metrics.exception.StoreNotFoundException;
import com.storeanalytics.metrics.repository.AverageKpiAggregate;
import com.storeanalytics.metrics.repository.AverageKpiRepository;
import com.storeanalytics.store.repository.StoreRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AverageKpiService {

    static final String FORMULA_VERSION = "average-kpi-v1";
    private static final int MONEY_SCALE = 2;
    private static final int QUANTITY_SCALE = 3;
    private static final int VALUE_SCALE = 0;
    private static final int PERCENT_SCALE = 1;
    private static final int CALCULATION_SCALE = 12;

    private final StoreRepository storeRepository;
    private final AverageKpiRepository averageKpiRepository;

    public AverageKpiService(
            StoreRepository storeRepository,
            AverageKpiRepository averageKpiRepository
    ) {
        this.storeRepository = storeRepository;
        this.averageKpiRepository = averageKpiRepository;
    }

    @Transactional(readOnly = true)
    public AverageKpiResult calculate(UUID storeId, StoreKpiPeriod period) {
        UUID validatedStoreId = requireNonNull(storeId, "storeId");
        StoreKpiPeriod validatedPeriod = requireNonNull(period, "period");
        if (!storeRepository.existsById(validatedStoreId)) {
            throw new StoreNotFoundException(validatedStoreId);
        }

        StoreKpiPeriod previousPeriod = previousPeriod(validatedPeriod);
        List<AverageKpiAggregate> aggregates = averageKpiRepository.aggregate(
                validatedStoreId,
                validatedPeriod.start(),
                validatedPeriod.end(),
                previousPeriod.start(),
                previousPeriod.end()
        );
        if (aggregates.isEmpty()) {
            throw new IllegalStateException("Analytics categories are not configured");
        }

        AverageKpiAggregate summary = aggregates.getFirst();
        AverageMetricComparison averageReceipt = comparison(
                calculation(summary.currentNetRevenue(), receipt(summary.currentReceiptCount())),
                calculation(summary.previousNetRevenue(), receipt(summary.previousReceiptCount()))
        );
        AverageMetricComparison additionalRevenuePerPhone = comparison(
                calculation(
                        summary.currentAdditionalRevenue(),
                        quantity(summary.currentPhoneQuantity())
                ),
                calculation(
                        summary.previousAdditionalRevenue(),
                        quantity(summary.previousPhoneQuantity())
                )
        );
        List<CategoryAverageEntry> categories = aggregates.stream()
                .map(this::category)
                .toList();
        return new AverageKpiResult(
                validatedStoreId,
                validatedPeriod.start(),
                validatedPeriod.end(),
                previousPeriod.start(),
                previousPeriod.end(),
                FORMULA_VERSION,
                averageReceipt,
                additionalRevenuePerPhone,
                categories
        );
    }

    private StoreKpiPeriod previousPeriod(StoreKpiPeriod current) {
        long duration = ChronoUnit.DAYS.between(current.start(), current.end()) + 1;
        LocalDate previousEnd = current.start().minusDays(1);
        return new StoreKpiPeriod(previousEnd.minusDays(duration - 1), previousEnd);
    }

    private CategoryAverageEntry category(AverageKpiAggregate aggregate) {
        return new CategoryAverageEntry(
                aggregate.categoryCode(),
                aggregate.categoryName(),
                aggregate.categoryActive(),
                comparison(
                        calculation(
                                aggregate.currentCategoryRevenue(),
                                quantity(aggregate.currentCategoryQuantity())
                        ),
                        calculation(
                                aggregate.previousCategoryRevenue(),
                                quantity(aggregate.previousCategoryQuantity())
                        )
                )
        );
    }

    private MetricCalculation calculation(BigDecimal numerator, BigDecimal denominator) {
        BigDecimal scaledNumerator = money(numerator);
        BigDecimal rawValue = denominator.signum() <= 0
                ? null
                : scaledNumerator.divide(
                        denominator,
                        CALCULATION_SCALE,
                        RoundingMode.HALF_UP
                );
        BigDecimal value = rawValue == null
                ? null
                : rawValue.setScale(VALUE_SCALE, RoundingMode.HALF_UP);
        return new MetricCalculation(
                new AverageMetricSnapshot(scaledNumerator, denominator, value),
                rawValue
        );
    }

    private AverageMetricComparison comparison(
            MetricCalculation current,
            MetricCalculation previous
    ) {
        BigDecimal changePercent = current.rawValue() == null
                || previous.rawValue() == null
                || previous.rawValue().signum() == 0
                ? null
                : current.rawValue().subtract(previous.rawValue())
                        .multiply(BigDecimal.valueOf(100))
                        .divide(previous.rawValue(), PERCENT_SCALE, RoundingMode.HALF_UP);
        return new AverageMetricComparison(
                current.snapshot(),
                previous.snapshot(),
                changePercent
        );
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
    }

    private BigDecimal quantity(BigDecimal value) {
        return value.setScale(QUANTITY_SCALE, RoundingMode.UNNECESSARY);
    }

    private BigDecimal receipt(long value) {
        return BigDecimal.valueOf(value);
    }

    private record MetricCalculation(
            AverageMetricSnapshot snapshot,
            BigDecimal rawValue
    ) {
    }
}
