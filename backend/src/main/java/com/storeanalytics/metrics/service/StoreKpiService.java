package com.storeanalytics.metrics.service;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.metrics.exception.StoreNotFoundException;
import com.storeanalytics.metrics.repository.StoreKpiAggregate;
import com.storeanalytics.metrics.repository.StoreKpiRepository;
import com.storeanalytics.quality.repository.PeriodQualityIssueRepository;
import com.storeanalytics.store.repository.StoreRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StoreKpiService {

    static final String FORMULA_VERSION = "store-kpi-v1";
    private static final int MONEY_SCALE = 2;
    private static final int QUANTITY_SCALE = 3;
    private static final int PERCENT_SCALE = 2;

    private final StoreRepository storeRepository;
    private final StoreKpiRepository storeKpiRepository;
    private final PeriodQualityIssueRepository periodQualityIssueRepository;

    public StoreKpiService(
            StoreRepository storeRepository,
            StoreKpiRepository storeKpiRepository,
            PeriodQualityIssueRepository periodQualityIssueRepository
    ) {
        this.storeRepository = storeRepository;
        this.storeKpiRepository = storeKpiRepository;
        this.periodQualityIssueRepository = periodQualityIssueRepository;
    }

    @Transactional(readOnly = true)
    public StoreKpiResult calculate(UUID storeId, StoreKpiPeriod period) {
        UUID validatedStoreId = requireNonNull(storeId, "storeId");
        StoreKpiPeriod validatedPeriod = requireNonNull(period, "period");
        if (!storeRepository.existsById(validatedStoreId)) {
            throw new StoreNotFoundException(validatedStoreId);
        }

        StoreKpiAggregate aggregate = storeKpiRepository.aggregate(
                validatedStoreId,
                validatedPeriod.start(),
                validatedPeriod.end()
        );
        long periodOpenConsistencyIssueCount = periodQualityIssueRepository
                .countOpenConsistencyIssues(
                        validatedStoreId, validatedPeriod.start(), validatedPeriod.end()
                );
        BigDecimal netRevenue = money(aggregate.netRevenue());
        BigDecimal netQuantity = quantity(aggregate.netQuantity());
        boolean completeCostData = aggregate.missingCostItemCount() == 0;
        BigDecimal costAmount = completeCostData ? money(aggregate.costAmount()) : null;
        BigDecimal grossProfit = completeCostData
                ? money(netRevenue.subtract(costAmount))
                : null;
        BigDecimal marginPercent = grossProfit == null || netRevenue.signum() == 0
                ? null
                : grossProfit.multiply(BigDecimal.valueOf(100))
                        .divide(netRevenue, PERCENT_SCALE, RoundingMode.HALF_UP);

        StoreKpiDataQuality dataQuality = new StoreKpiDataQuality(
                completeCostData,
                aggregate.includedItemCount(),
                aggregate.unmappedItemCount(),
                aggregate.missingCostItemCount(),
                aggregate.unexpectedZeroCostItemCount(),
                periodOpenConsistencyIssueCount,
                aggregate.storeOpenQualityIssueCount()
        );
        return new StoreKpiResult(
                validatedStoreId,
                validatedPeriod.start(),
                validatedPeriod.end(),
                FORMULA_VERSION,
                netRevenue,
                netQuantity,
                costAmount,
                grossProfit,
                marginPercent,
                dataQuality
        );
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
    }

    private BigDecimal quantity(BigDecimal value) {
        return value.setScale(QUANTITY_SCALE, RoundingMode.UNNECESSARY);
    }
}
