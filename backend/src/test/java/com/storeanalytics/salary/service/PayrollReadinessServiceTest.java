package com.storeanalytics.salary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeanalytics.performance.model.StorePerformancePlan;
import com.storeanalytics.performance.repository.EmployeeWorkShiftRepository;
import com.storeanalytics.performance.repository.StorePerformancePlanRepository;
import com.storeanalytics.salary.model.PayrollScheme;
import com.storeanalytics.salary.model.PayrollSchemeDefinition;
import com.storeanalytics.salary.repository.PayrollReadinessRepository;
import com.storeanalytics.salary.repository.PayrollSaleSourceFact;
import com.storeanalytics.salary.repository.PayrollSalesRepository;
import com.storeanalytics.salary.repository.PayrollSchemeRepository;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.repository.StoreRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PayrollReadinessServiceTest {

    private static final YearMonth MONTH = YearMonth.of(2026, 7);

    private StoreRepository storeRepository;
    private StorePerformancePlanRepository planRepository;
    private PayrollSchemeRepository schemeRepository;
    private PayrollSalesRepository salesRepository;
    private EmployeeWorkShiftRepository shiftRepository;
    private PayrollReadinessRepository readinessRepository;
    private PayrollReadinessService service;

    @BeforeEach
    void setUp() {
        storeRepository = mock(StoreRepository.class);
        planRepository = mock(StorePerformancePlanRepository.class);
        schemeRepository = mock(PayrollSchemeRepository.class);
        salesRepository = mock(PayrollSalesRepository.class);
        shiftRepository = mock(EmployeeWorkShiftRepository.class);
        readinessRepository = mock(PayrollReadinessRepository.class);
        service = new PayrollReadinessService(
                storeRepository,
                planRepository,
                schemeRepository,
                salesRepository,
                shiftRepository,
                readinessRepository
        );
    }

    @Test
    void reportsFundBearingDayWithoutShiftAsCorrectionRequired() {
        UUID storeId = UUID.randomUUID();
        LocalDate workDate = LocalDate.of(2026, 7, 10);
        StorePerformancePlan plan = mock(StorePerformancePlan.class);
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(mock(Store.class)));
        when(plan.getRevenueTarget()).thenReturn(new BigDecimal("500.00"));
        when(plan.getAccessoryShareTarget()).thenReturn(new BigDecimal("3.90"));
        when(plan.getServiceShareTarget()).thenReturn(new BigDecimal("3.00"));
        when(planRepository.findByStoreIdAndPlanMonth(storeId, MONTH.atDay(1)))
                .thenReturn(Optional.of(plan));
        when(schemeRepository.findFirstByEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                MONTH.atDay(1)
        )).thenReturn(Optional.of(scheme()));
        when(salesRepository.sourceFacts(
                storeId, MONTH.atDay(1), MONTH.atEndOfMonth()
        )).thenReturn(List.of(
                fact(workDate, "ACCESSORY", "100.00"),
                fact(workDate, "OTHER", "900.00")
        ));
        when(shiftRepository
                .findAllByStoreIdAndWorkDateBetweenOrderByWorkDateAscEmployeeFullNameAsc(
                        storeId, MONTH.atDay(1), MONTH.atEndOfMonth()
                )).thenReturn(List.of());
        when(readinessRepository.unmappedProducts(
                storeId, MONTH.atDay(1), MONTH.atEndOfMonth()
        )).thenReturn(List.of());
        when(readinessRepository.missingCosts(
                storeId, MONTH.atDay(1), MONTH.atEndOfMonth()
        )).thenReturn(List.of());

        PayrollReadinessView result = service.inspect(storeId, MONTH);

        assertThat(result.status()).isEqualTo(PayrollReadinessStatus.NEEDS_CORRECTION);
        assertThat(result.canCalculate()).isTrue();
        assertThat(result.canApprove()).isFalse();
        assertThat(result.shiftIssues()).singleElement().satisfies(issue -> {
            assertThat(issue.workDate()).isEqualTo(workDate);
            assertThat(issue.fundAmount()).isEqualByComparingTo("20.00");
        });
    }

    private PayrollSaleSourceFact fact(
            LocalDate workDate,
            String category,
            String amount
    ) {
        return new PayrollSaleSourceFact(
                UUID.randomUUID(),
                workDate,
                1,
                BigDecimal.ONE,
                new BigDecimal(amount),
                BigDecimal.ZERO.setScale(2),
                UUID.randomUUID(),
                UUID.randomUUID(),
                category,
                null,
                category,
                null,
                null,
                false
        );
    }

    private PayrollScheme scheme() {
        return new PayrollScheme(
                "seller-payroll-v1",
                LocalDate.of(1970, 1, 1),
                new PayrollSchemeDefinition(
                        new BigDecimal("20.00"),
                        new BigDecimal("15.00"),
                        new BigDecimal("500.00"),
                        new BigDecimal("400.00"),
                        new BigDecimal("300.00"),
                        new BigDecimal("200.00"),
                        new BigDecimal("50000.00")
                ),
                null
        );
    }
}
