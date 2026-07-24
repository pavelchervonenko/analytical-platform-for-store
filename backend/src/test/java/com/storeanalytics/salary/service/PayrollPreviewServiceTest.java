package com.storeanalytics.salary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeanalytics.employee.model.Employee;
import com.storeanalytics.performance.model.EmployeeWorkShift;
import com.storeanalytics.performance.model.StorePerformancePlan;
import com.storeanalytics.salary.model.PayrollScheme;
import com.storeanalytics.salary.model.PayrollSchemeDefinition;
import com.storeanalytics.salary.repository.PayrollAdjustmentRepository;
import com.storeanalytics.salary.repository.PayrollDailySalesAggregate;
import com.storeanalytics.salary.repository.PayrollRunRepository;
import com.storeanalytics.store.model.Store;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PayrollPreviewServiceTest {

    private static final YearMonth MONTH = YearMonth.of(2026, 7);
    private static final LocalDate WORK_DATE = LocalDate.of(2026, 7, 10);

    private PayrollCalculationSource source;
    private PayrollReadinessService readinessService;
    private PayrollRunRepository runRepository;
    private PayrollAdjustmentRepository adjustmentRepository;
    private PayrollPreviewService service;

    @BeforeEach
    void setUp() {
        source = mock(PayrollCalculationSource.class);
        readinessService = mock(PayrollReadinessService.class);
        runRepository = mock(PayrollRunRepository.class);
        adjustmentRepository = mock(PayrollAdjustmentRepository.class);
        service = new PayrollPreviewService(
                source, readinessService, runRepository, adjustmentRepository
        );
    }

    @Test
    void returnsActualIndependentPlanScenarioWithoutCreatingPayrollState() {
        UUID storeId = UUID.randomUUID();
        Store store = mock(Store.class);
        StorePerformancePlan plan = mock(StorePerformancePlan.class);
        Employee employee = mock(Employee.class);
        EmployeeWorkShift shift = mock(EmployeeWorkShift.class);
        PayrollReadinessView readiness = mock(PayrollReadinessView.class);
        when(store.getId()).thenReturn(storeId);
        when(plan.getPlanMonth()).thenReturn(MONTH.atDay(1));
        when(plan.getRevenueTarget()).thenReturn(new BigDecimal("1000.00"));
        when(plan.getAccessoryShareTarget()).thenReturn(new BigDecimal("3.90"));
        when(plan.getServiceShareTarget()).thenReturn(new BigDecimal("3.00"));
        when(employee.getId()).thenReturn(UUID.randomUUID());
        when(employee.getFullName()).thenReturn("Алина");
        when(shift.getWorkDate()).thenReturn(WORK_DATE);
        when(shift.getEmployee()).thenReturn(employee);
        when(shift.getWorkedHours()).thenReturn(new BigDecimal("6.50"));
        when(source.load(storeId, MONTH)).thenReturn(new PayrollCalculationSourceData(
                store,
                plan,
                scheme(),
                List.of(new PayrollDailySalesAggregate(
                        WORK_DATE,
                        new BigDecimal("1200.00"),
                        new BigDecimal("1000.00"),
                        BigDecimal.ZERO.setScale(2),
                        new BigDecimal("100.00"),
                        new BigDecimal("50.00"),
                        new BigDecimal("2.000"),
                        new BigDecimal("3.000"),
                        0,
                        0
                )),
                List.of(shift)
        ));
        when(readinessService.inspect(storeId, MONTH)).thenReturn(readiness);
        when(runRepository.findFirstByStoreIdAndPeriodMonthOrderByRevisionDesc(
                storeId, MONTH.atDay(1)
        )).thenReturn(Optional.empty());

        PayrollPreviewView result = service.preview(storeId, MONTH);

        assertThat(result.persisted()).isFalse();
        assertThat(result.planResult().revenueAchieved()).isTrue();
        assertThat(result.planResult().accessoryAchieved()).isTrue();
        assertThat(result.planResult().serviceAchieved()).isFalse();
        assertThat(result.actualScenario().totalFundAmount())
                .isEqualByComparingTo("2122.50");
        assertThat(result.actualScenario().employees()).singleElement()
                .extracting(PayrollPreviewEmployeeView::payableAmount)
                .isEqualTo(new BigDecimal("-47877.50"));
        assertThat(result.actualScenario().employees()).singleElement()
                .extracting(PayrollPreviewEmployeeView::workedHours)
                .isEqualTo(new BigDecimal("6.50"));
        assertThat(result.actualScenario().days()).singleElement()
                .satisfies(day -> assertThat(day.allocations()).singleElement()
                        .extracting(PayrollPreviewAllocationView::workedHours)
                        .isEqualTo(new BigDecimal("6.50")));
        verify(runRepository).findFirstByStoreIdAndPeriodMonthOrderByRevisionDesc(
                storeId, MONTH.atDay(1)
        );
        verifyNoInteractions(adjustmentRepository);
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
