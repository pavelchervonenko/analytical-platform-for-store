package com.storeanalytics.salary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeanalytics.salary.model.PayrollPlanResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PayrollRevisionComparisonServiceTest {

    private PayrollManagementService payrollService;
    private PayrollRevisionComparisonService service;

    @BeforeEach
    void setUp() {
        payrollService = mock(PayrollManagementService.class);
        service = new PayrollRevisionComparisonService(payrollService);
    }

    @Test
    void includesEmployeeWhoseShiftCountChangedEvenWhenMoneyDidNot() {
        UUID storeId = UUID.randomUUID();
        UUID previousRunId = UUID.randomUUID();
        UUID currentRunId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        UUID schemeId = UUID.randomUUID();
        PayrollRunSummaryView previousRun = run(previousRunId);
        PayrollRunSummaryView currentRun = run(currentRunId);
        PayrollSchemeView scheme = mock(PayrollSchemeView.class);
        when(scheme.id()).thenReturn(schemeId);
        PayrollRunDetailView previous = detail(
                previousRun, scheme, statement(employeeId, 1)
        );
        PayrollRunDetailView current = detail(
                currentRun, scheme, statement(employeeId, 2)
        );
        when(payrollService.get(storeId, previousRunId)).thenReturn(previous);
        when(payrollService.get(storeId, currentRunId)).thenReturn(current);

        PayrollRevisionComparisonView result = service.compare(
                storeId, previousRunId, currentRunId
        );

        assertThat(result.totalFundChange()).isEqualByComparingTo("0.00");
        assertThat(result.totalPayableChange()).isEqualByComparingTo("0.00");
        assertThat(result.employeeChanges()).singleElement().satisfies(change -> {
            assertThat(change.employeeId()).isEqualTo(employeeId);
            assertThat(change.reasons()).containsExactly("SHIFT_CHANGED");
            assertThat(change.payableChange()).isEqualByComparingTo("0.00");
        });
    }

    private PayrollRunDetailView detail(
            PayrollRunSummaryView run,
            PayrollSchemeView scheme,
            PayrollStatementView statement
    ) {
        return new PayrollRunDetailView(
                run, scheme, List.of(), List.of(), List.of(), List.of(statement), List.of()
        );
    }

    private PayrollRunSummaryView run(UUID id) {
        PayrollRunSummaryView run = mock(PayrollRunSummaryView.class);
        when(run.planResult()).thenReturn(mock(PayrollPlanResult.class));
        when(run.id()).thenReturn(id);
        when(run.periodMonth()).thenReturn(LocalDate.of(2026, 7, 1));
        return run;
    }

    private PayrollStatementView statement(UUID employeeId, int shifts) {
        return new PayrollStatementView(
                UUID.randomUUID(),
                employeeId,
                "Алина",
                shifts,
                BigDecimal.valueOf(shifts * 11L).setScale(2),
                new BigDecimal("1000.00"),
                new BigDecimal("500.00"),
                BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(2),
                new BigDecimal("500.00")
        );
    }
}
