package com.storeanalytics.metrics.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storeanalytics.common.web.ApiExceptionHandler;
import com.storeanalytics.metrics.service.CategoryKpiDataQuality;
import com.storeanalytics.metrics.service.EmployeeCategoryKpiEmployee;
import com.storeanalytics.metrics.service.EmployeeCategoryKpiEntry;
import com.storeanalytics.metrics.service.EmployeeCategoryKpiGroup;
import com.storeanalytics.metrics.service.EmployeeCategoryKpiMetrics;
import com.storeanalytics.metrics.service.EmployeeCategoryKpiResult;
import com.storeanalytics.metrics.service.EmployeeCategoryKpiService;
import com.storeanalytics.metrics.service.EmployeeKpiDataQuality;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import com.storeanalytics.product.model.AnalyticsCategoryKind;
import com.storeanalytics.product.model.DeviceFamily;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class EmployeeCategoryKpiControllerTest {

    private EmployeeCategoryKpiService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(EmployeeCategoryKpiService.class);
        JsonMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new EmployeeCategoryKpiController(service))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(objectMapper))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void returnsExplicitInclusiveProjection() throws Exception {
        UUID storeId = UUID.randomUUID();
        when(service.calculate(eq(storeId), any(StoreKpiPeriod.class)))
                .thenReturn(result(storeId));

        mockMvc.perform(get(
                        "/api/stores/{storeId}/kpi/employees/categories",
                        storeId
                )
                        .queryParam("periodStart", "2026-07-20")
                        .queryParam("periodEnd", "2026-07-26"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.formulaVersion")
                        .value("employee-category-kpi-v1"))
                .andExpect(jsonPath("$.categoryFormulaVersion")
                        .value("category-kpi-v2"))
                .andExpect(jsonPath("$.employees[0].rankingEligible").value(true))
                .andExpect(jsonPath("$.employees[0].groups[0].groupCode")
                        .value("SERVICE"))
                .andExpect(jsonPath(
                        "$.employees[0].groups[0].metrics.revenueSharePercent"
                ).value(50.00))
                .andExpect(jsonPath("$.employees[0].categories[0].categoryCode")
                        .value("SETUP_SERVICE"));

        ArgumentCaptor<StoreKpiPeriod> period =
                ArgumentCaptor.forClass(StoreKpiPeriod.class);
        verify(service).calculate(eq(storeId), period.capture());
        org.assertj.core.api.Assertions.assertThat(period.getValue().start())
                .isEqualTo(LocalDate.of(2026, 7, 20));
        org.assertj.core.api.Assertions.assertThat(period.getValue().end())
                .isEqualTo(LocalDate.of(2026, 7, 26));
    }

    @Test
    void rejectsReversedPeriod() throws Exception {
        mockMvc.perform(get(
                        "/api/stores/{storeId}/kpi/employees/categories",
                        UUID.randomUUID()
                )
                        .queryParam("periodStart", "2026-07-26")
                        .queryParam("periodEnd", "2026-07-20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    private EmployeeCategoryKpiResult result(UUID storeId) {
        EmployeeCategoryKpiMetrics metrics = new EmployeeCategoryKpiMetrics(
                new BigDecimal("50.00"),
                new BigDecimal("1.000"),
                new BigDecimal("10.00"),
                new BigDecimal("40.00"),
                new BigDecimal("80.00"),
                new BigDecimal("50.00"),
                new CategoryKpiDataQuality(true, 1, 0, 0)
        );
        EmployeeCategoryKpiEmployee employee = new EmployeeCategoryKpiEmployee(
                UUID.randomUUID(),
                "Сотрудник",
                true,
                true,
                true,
                true,
                true,
                false,
                new BigDecimal("100.00"),
                new EmployeeKpiDataQuality(true, 1, 0, 0, 0),
                List.of(new EmployeeCategoryKpiGroup("SERVICE", "Услуги", metrics)),
                List.of(new EmployeeCategoryKpiEntry(
                        "SETUP_SERVICE",
                        "Настройка",
                        AnalyticsCategoryKind.SERVICE,
                        DeviceFamily.NONE,
                        true,
                        false,
                        false,
                        true,
                        metrics
                ))
        );
        return new EmployeeCategoryKpiResult(
                storeId,
                LocalDate.of(2026, 7, 20),
                LocalDate.of(2026, 7, 26),
                "employee-category-kpi-v1",
                "category-kpi-v2",
                List.of(employee)
        );
    }
}
