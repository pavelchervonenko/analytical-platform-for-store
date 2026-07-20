package com.storeanalytics.metrics.service;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.metrics.exception.StoreNotFoundException;
import com.storeanalytics.metrics.repository.EmployeeKpiAggregate;
import com.storeanalytics.metrics.repository.EmployeeKpiRepository;
import com.storeanalytics.store.repository.StoreRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmployeeKpiService {

    private static final String UNASSIGNED_DISPLAY_NAME = "Не назначен";

    private static final int MONEY_SCALE = 2;
    private static final int QUANTITY_SCALE = 3;
    private static final int PERCENT_SCALE = 2;

    private final StoreRepository storeRepository;
    private final EmployeeKpiRepository employeeKpiRepository;

    public EmployeeKpiService(
            StoreRepository storeRepository,
            EmployeeKpiRepository employeeKpiRepository
    ) {
        this.storeRepository = storeRepository;
        this.employeeKpiRepository = employeeKpiRepository;
    }

    @Transactional(readOnly = true)
    public EmployeeKpiResult calculate(UUID storeId, StoreKpiPeriod period) {
        UUID validatedStoreId = requireNonNull(storeId, "storeId");
        StoreKpiPeriod validatedPeriod = requireNonNull(period, "period");
        if (!storeRepository.existsById(validatedStoreId)) {
            throw new StoreNotFoundException(validatedStoreId);
        }

        List<EmployeeKpiEntry> employees = employeeKpiRepository.aggregate(
                        validatedStoreId,
                        validatedPeriod.start(),
                        validatedPeriod.end()
                ).stream()
                .map(this::toEntry)
                .toList();
        return new EmployeeKpiResult(
                validatedStoreId,
                validatedPeriod.start(),
                validatedPeriod.end(),
                StoreKpiService.FORMULA_VERSION,
                employees
        );
    }

    private EmployeeKpiEntry toEntry(EmployeeKpiAggregate aggregate) {
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
        boolean rankingEligible = !aggregate.unassigned()
                && aggregate.employeeActive()
                && aggregate.assignmentActive()
                && aggregate.participatesInRanking();

        return new EmployeeKpiEntry(
                aggregate.employeeId(),
                aggregate.unassigned()
                        ? UNASSIGNED_DISPLAY_NAME
                        : aggregate.displayName(),
                aggregate.employeeActive(),
                aggregate.assignedToStore(),
                aggregate.assignmentActive(),
                aggregate.participatesInRanking(),
                rankingEligible,
                aggregate.unassigned(),
                netRevenue,
                netQuantity,
                costAmount,
                grossProfit,
                marginPercent,
                new EmployeeKpiDataQuality(
                        completeCostData,
                        aggregate.includedItemCount(),
                        aggregate.unmappedItemCount(),
                        aggregate.missingCostItemCount(),
                        aggregate.unexpectedZeroCostItemCount()
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
