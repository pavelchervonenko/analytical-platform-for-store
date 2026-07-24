package com.storeanalytics.metrics.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeanalytics.common.web.ApiExceptionHandler;
import com.storeanalytics.metrics.service.EmployeeKpiDataQuality;
import com.storeanalytics.metrics.service.EmployeeKpiEntry;
import com.storeanalytics.metrics.service.EmployeeKpiResult;
import com.storeanalytics.metrics.service.EmployeeKpiService;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class EmployeeKpiControllerTest {

    private EmployeeKpiService employeeKpiService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        employeeKpiService = mock(EmployeeKpiService.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new EmployeeKpiController(employeeKpiService))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(
                        new ApiExceptionHandler()
                )
                .build();
    }

    @Test
    void returnsEmployeeBreakdownIncludingRankingEligibility() throws Exception {
        UUID storeId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        when(employeeKpiService.calculate(any(UUID.class), any(StoreKpiPeriod.class)))
                .thenReturn(result(storeId, employeeId));

        mockMvc.perform(get("/api/stores/{storeId}/kpi/employees", storeId)
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd", "2026-07-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value(storeId.toString()))
                .andExpect(jsonPath("$.periodStart").value("2026-07-01"))
                .andExpect(jsonPath("$.formulaVersion").value("store-kpi-v1"))
                .andExpect(jsonPath("$.employees[0].employeeId").value(employeeId.toString()))
                .andExpect(jsonPath("$.employees[0].displayName").value("Employee"))
                .andExpect(jsonPath("$.employees[0].rankingEligible").value(true))
                .andExpect(jsonPath("$.employees[0].netRevenue").value(300.00));
    }

    @Test
    void rejectsReversedPeriod() throws Exception {
        UUID storeId = UUID.randomUUID();

        mockMvc.perform(get("/api/stores/{storeId}/kpi/employees", storeId)
                        .queryParam("periodStart", "2026-07-31")
                        .queryParam("periodEnd", "2026-07-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void rejectsMalformedDate() throws Exception {
        UUID storeId = UUID.randomUUID();

        mockMvc.perform(get("/api/stores/{storeId}/kpi/employees", storeId)
                        .queryParam("periodStart", "invalid")
                        .queryParam("periodEnd", "2026-07-31"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    private EmployeeKpiResult result(UUID storeId, UUID employeeId) {
        EmployeeKpiEntry entry = new EmployeeKpiEntry(
                employeeId,
                "Employee",
                true,
                true,
                true,
                true,
                true,
                false,
                new BigDecimal("300.00"),
                new BigDecimal("3.000"),
                new BigDecimal("200.00"),
                new BigDecimal("100.00"),
                new BigDecimal("33.33"),
                new EmployeeKpiDataQuality(true, 2, 0, 0, 0)
        );
        return new EmployeeKpiResult(
                storeId,
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31),
                "store-kpi-v1",
                List.of(entry)
        );
    }
}
