package com.storeanalytics.salary.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.json.JsonMapper;
import com.storeanalytics.auth.security.AppUserPrincipal;
import com.storeanalytics.common.web.ApiExceptionHandler;
import com.storeanalytics.salary.exception.PayrollSourceDataChangedException;
import com.storeanalytics.salary.model.PayrollAdjustmentType;
import com.storeanalytics.salary.service.AddPayrollAdjustmentCommand;
import com.storeanalytics.salary.service.PayrollManagementService;
import com.storeanalytics.salary.service.PayrollRunDetailView;
import com.storeanalytics.salary.service.PayrollStaleReason;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PayrollControllerTest {

    private PayrollManagementService payrollService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        payrollService = mock(PayrollManagementService.class);
        JsonMapper objectMapper = JsonMapper.builder()
                .findAndAddModules()
                .build();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new PayrollController(payrollService))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(objectMapper))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void calculatesMonthAndCreatesManualDeduction() throws Exception {
        UUID storeId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        PayrollRunDetailView detail = emptyDetail();
        when(payrollService.calculate(
                storeId,
                YearMonth.of(2026, 7),
                null,
                actorId,
                "payroll-calculate-1234"
        )).thenReturn(detail);
        AddPayrollAdjustmentCommand command = new AddPayrollAdjustmentCommand(
                storeId,
                runId,
                employeeId,
                PayrollAdjustmentType.PENALTY,
                new BigDecimal("1500.00"),
                "Опоздание",
                3,
                actorId
        );
        when(payrollService.addAdjustment(command, "payroll-adjustment-1234"))
                .thenReturn(detail);

        mockMvc.perform(post("/api/stores/{storeId}/payroll/{month}/calculate", storeId, "2026-07")
                        .principal(authentication(actorId))
                        .header("Idempotency-Key", "payroll-calculate-1234")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        mockMvc.perform(post(
                        "/api/stores/{storeId}/payroll-runs/{runId}/adjustments",
                        storeId,
                        runId
                ).principal(authentication(actorId))
                        .header("Idempotency-Key", "payroll-adjustment-1234")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "employeeId": "%s",
                                  "type": "PENALTY",
                                  "amount": 1500.00,
                                  "reason": "Опоздание",
                                  "runVersion": 3
                                }
                                """.formatted(employeeId)))
                .andExpect(status().isOk());

        verify(payrollService).calculate(
                storeId,
                YearMonth.of(2026, 7),
                null,
                actorId,
                "payroll-calculate-1234"
        );
        verify(payrollService).addAdjustment(command, "payroll-adjustment-1234");
    }

    @Test
    void rejectsNonPositiveDeduction() throws Exception {
        UUID storeId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();

        mockMvc.perform(post(
                        "/api/stores/{storeId}/payroll-runs/{runId}/adjustments",
                        storeId,
                        runId
                ).principal(authentication(UUID.randomUUID()))
                        .header("Idempotency-Key", "invalid-adjustment-1234")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "employeeId": "%s",
                                  "type": "TAX",
                                  "amount": 0,
                                  "reason": "Налог",
                                  "runVersion": 0
                                }
                                """.formatted(employeeId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void returnsStableConflictWhenApprovalSourcesChanged() throws Exception {
        UUID storeId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        when(payrollService.approve(
                storeId, runId, 4, actorId, "payroll-approve-1234"
        ))
                .thenThrow(new PayrollSourceDataChangedException(List.of(
                        PayrollStaleReason.SALES_DATA_CHANGED
                )));

        mockMvc.perform(post(
                        "/api/stores/{storeId}/payroll-runs/{runId}/approve",
                        storeId,
                        runId
                ).principal(authentication(actorId))
                        .header("Idempotency-Key", "payroll-approve-1234")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":4}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYROLL_SOURCE_DATA_CHANGED"));
    }

    @Test
    void requiresIdempotencyKeyForPayrollCommands() throws Exception {
        UUID storeId = UUID.randomUUID();

        mockMvc.perform(post(
                        "/api/stores/{storeId}/payroll/{month}/calculate",
                        storeId,
                        "2026-07"
                ).principal(authentication(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"));
    }

    private PayrollRunDetailView emptyDetail() {
        return new PayrollRunDetailView(
                null, null, List.of(), List.of(), List.of(), List.of(), List.of()
        );
    }

    private TestingAuthenticationToken authentication(UUID actorId) {
        AppUserPrincipal principal = mock(AppUserPrincipal.class);
        when(principal.getUserId()).thenReturn(actorId);
        return new TestingAuthenticationToken(principal, null);
    }
}
