package com.storeanalytics.performance.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeanalytics.auth.model.UserFeature;
import com.storeanalytics.auth.security.AppUserPrincipal;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import com.storeanalytics.performance.service.EmployeeCardService;
import com.storeanalytics.performance.service.EmployeeCardView;
import com.storeanalytics.performance.service.EmployeeComparisonMode;
import com.storeanalytics.performance.service.EmployeePayrollContextView;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

class EmployeeCardControllerTest {

    @Test
    void hidesPayrollContextWithoutPayrollFeature() {
        UUID storeId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        LocalDate start = LocalDate.of(2026, 7, 1);
        LocalDate end = LocalDate.of(2026, 7, 31);
        EmployeeCardService service = mock(EmployeeCardService.class);
        EmployeeCardView source = view(storeId, employeeId, start, end);
        when(service.card(
                storeId,
                employeeId,
                new StoreKpiPeriod(start, end),
                EmployeeComparisonMode.PREVIOUS_PERIOD
        )).thenReturn(source);
        EmployeeCardController controller = new EmployeeCardController(service);

        EmployeeCardView restricted = controller.card(
                storeId,
                employeeId,
                start,
                end,
                EmployeeComparisonMode.PREVIOUS_PERIOD,
                authentication(false)
        );
        EmployeeCardView permitted = controller.card(
                storeId,
                employeeId,
                start,
                end,
                EmployeeComparisonMode.PREVIOUS_PERIOD,
                authentication(true)
        );

        assertThat(restricted.payroll()).isNull();
        assertThat(permitted.payroll()).isSameAs(source.payroll());
    }

    private EmployeeCardView view(
            UUID storeId,
            UUID employeeId,
            LocalDate start,
            LocalDate end
    ) {
        return new EmployeeCardView(
                storeId,
                employeeId,
                start,
                end,
                start.minusMonths(1),
                end.minusMonths(1),
                null,
                null,
                null,
                null,
                null,
                new EmployeePayrollContextView(null, null)
        );
    }

    private Authentication authentication(boolean payrollAllowed) {
        AppUserPrincipal principal = mock(AppUserPrincipal.class);
        when(principal.hasFeature(UserFeature.PAYROLL)).thenReturn(payrollAllowed);
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(principal);
        return authentication;
    }
}
