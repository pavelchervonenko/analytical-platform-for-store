package com.storeanalytics.salary.service;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.metrics.exception.StoreNotFoundException;
import com.storeanalytics.performance.exception.PerformancePlanNotFoundException;
import com.storeanalytics.performance.model.EmployeeWorkShift;
import com.storeanalytics.performance.model.StorePerformancePlan;
import com.storeanalytics.performance.repository.EmployeeWorkShiftRepository;
import com.storeanalytics.performance.repository.StorePerformancePlanRepository;
import com.storeanalytics.salary.model.PayrollScheme;
import com.storeanalytics.salary.repository.PayrollDailySalesAggregate;
import com.storeanalytics.salary.repository.PayrollSaleSourceFact;
import com.storeanalytics.salary.repository.PayrollSalesRepository;
import com.storeanalytics.salary.repository.PayrollSchemeRepository;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.repository.StoreRepository;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class PayrollCalculationSource {

    private final StoreRepository storeRepository;
    private final StorePerformancePlanRepository planRepository;
    private final PayrollSchemeRepository schemeRepository;
    private final PayrollSalesRepository salesRepository;
    private final EmployeeWorkShiftRepository shiftRepository;

    PayrollCalculationSource(
            StoreRepository storeRepository,
            StorePerformancePlanRepository planRepository,
            PayrollSchemeRepository schemeRepository,
            PayrollSalesRepository salesRepository,
            EmployeeWorkShiftRepository shiftRepository
    ) {
        this.storeRepository = storeRepository;
        this.planRepository = planRepository;
        this.schemeRepository = schemeRepository;
        this.salesRepository = salesRepository;
        this.shiftRepository = shiftRepository;
    }

    PayrollCalculationSourceData load(UUID storeId, YearMonth month) {
        UUID validatedStoreId = requireNonNull(storeId, "storeId");
        YearMonth validatedMonth = requireNonNull(month, "month");
        Store store = storeRepository.findById(validatedStoreId)
                .orElseThrow(() -> new StoreNotFoundException(validatedStoreId));
        LocalDate monthStart = validatedMonth.atDay(1);
        LocalDate monthEnd = validatedMonth.atEndOfMonth();
        StorePerformancePlan plan = planRepository
                .findByStoreIdAndPlanMonth(validatedStoreId, monthStart)
                .orElseThrow(() -> new PerformancePlanNotFoundException(
                        validatedStoreId, monthStart
                ));
        PayrollScheme scheme = schemeRepository
                .findFirstByEffectiveFromLessThanEqualOrderByEffectiveFromDesc(monthStart)
                .orElseThrow(() -> new IllegalStateException(
                        "No payroll scheme is effective for the requested month"
                ));
        List<PayrollSaleSourceFact> saleFacts = salesRepository.sourceFacts(
                validatedStoreId, monthStart, monthEnd
        );
        List<PayrollDailySalesAggregate> sales =
                new PayrollSaleFactAggregator().aggregate(saleFacts);
        List<EmployeeWorkShift> shifts = shiftRepository
                .findAllByStoreIdAndWorkDateBetweenOrderByWorkDateAscEmployeeFullNameAsc(
                        validatedStoreId, monthStart, monthEnd
                ).stream()
                .filter(EmployeeWorkShift::isActive)
                .toList();
        return new PayrollCalculationSourceData(
                store,
                plan,
                scheme,
                sales,
                shifts,
                saleFacts
        );
    }
}
