package com.storeanalytics.metrics.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.json.JsonMapper;
import com.storeanalytics.common.web.ApiExceptionHandler;
import com.storeanalytics.metrics.exception.StoreNotFoundException;
import com.storeanalytics.metrics.service.CategoryKpiDataQuality;
import com.storeanalytics.metrics.service.CategoryKpiEntry;
import com.storeanalytics.metrics.service.CategoryKpiGroup;
import com.storeanalytics.metrics.service.CategoryKpiMetrics;
import com.storeanalytics.metrics.service.CategoryKpiResult;
import com.storeanalytics.metrics.service.CategoryKpiService;
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

class CategoryKpiControllerTest {

    private CategoryKpiService categoryKpiService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        categoryKpiService = mock(CategoryKpiService.class);
        JsonMapper objectMapper = JsonMapper.builder()
                .findAndAddModules()
                .build();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new CategoryKpiController(categoryKpiService))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(objectMapper))
                .setControllerAdvice(
                        new ApiExceptionHandler()
                )
                .build();
    }

    @Test
    void returnsCategoryKpiForExplicitInclusivePeriod() throws Exception {
        UUID storeId = UUID.randomUUID();
        when(categoryKpiService.calculate(any(UUID.class), any(StoreKpiPeriod.class)))
                .thenReturn(result(storeId));

        mockMvc.perform(get("/api/stores/{storeId}/kpi/categories", storeId)
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd", "2026-07-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value(storeId.toString()))
                .andExpect(jsonPath("$.periodStart").value("2026-07-01"))
                .andExpect(jsonPath("$.periodEnd").value("2026-07-31"))
                .andExpect(jsonPath("$.formulaVersion").value("category-kpi-v2"))
                .andExpect(jsonPath("$.groups[0].groupCode").value("PHONES"))
                .andExpect(jsonPath("$.groups[0].metrics.netRevenue").value(250.00))
                .andExpect(jsonPath("$.groups[0].metrics.averageGrossProfitPerUnit")
                        .value(66.67))
                .andExpect(jsonPath("$.categories[0].categoryCode").value("IPHONE_NEW_ASIS"))
                .andExpect(jsonPath("$.categories[0].categoryKind").value("DEVICE"))
                .andExpect(jsonPath("$.categories[0].metrics.netQuantity").value(1.500))
                .andExpect(jsonPath("$.categories[0].metrics.dataQuality.completeCostData")
                        .value(true));

        ArgumentCaptor<StoreKpiPeriod> period =
                ArgumentCaptor.forClass(StoreKpiPeriod.class);
        verify(categoryKpiService).calculate(eq(storeId), period.capture());
        assertThat(period.getValue().start()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(period.getValue().end()).isEqualTo(LocalDate.of(2026, 7, 31));
    }

    @Test
    void rejectsReversedPeriod() throws Exception {
        UUID storeId = UUID.randomUUID();

        mockMvc.perform(get("/api/stores/{storeId}/kpi/categories", storeId)
                        .queryParam("periodStart", "2026-07-31")
                        .queryParam("periodEnd", "2026-07-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void rejectsMalformedDate() throws Exception {
        UUID storeId = UUID.randomUUID();

        mockMvc.perform(get("/api/stores/{storeId}/kpi/categories", storeId)
                        .queryParam("periodStart", "invalid")
                        .queryParam("periodEnd", "2026-07-31"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void reportsUnknownStore() throws Exception {
        UUID storeId = UUID.randomUUID();
        when(categoryKpiService.calculate(any(UUID.class), any(StoreKpiPeriod.class)))
                .thenThrow(new StoreNotFoundException(storeId));

        mockMvc.perform(get("/api/stores/{storeId}/kpi/categories", storeId)
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd", "2026-07-31"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STORE_NOT_FOUND"));
    }

    private CategoryKpiResult result(UUID storeId) {
        CategoryKpiMetrics metrics = new CategoryKpiMetrics(
                new BigDecimal("250.00"),
                new BigDecimal("1.500"),
                new BigDecimal("150.00"),
                new BigDecimal("100.00"),
                new BigDecimal("66.67"),
                new BigDecimal("40.00"),
                new CategoryKpiDataQuality(true, 2, 0, 0)
        );
        return new CategoryKpiResult(
                storeId,
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31),
                "category-kpi-v2",
                List.of(new CategoryKpiGroup("PHONES", "Телефоны", metrics)),
                List.of(new CategoryKpiEntry(
                        "IPHONE_NEW_ASIS",
                        "iPhone New/ASIS+",
                        AnalyticsCategoryKind.DEVICE,
                        DeviceFamily.IPHONE,
                        true,
                        true,
                        true,
                        false,
                        metrics
                ))
        );
    }
}
