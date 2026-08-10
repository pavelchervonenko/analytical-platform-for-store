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
import com.storeanalytics.metrics.service.StoreKpiDataQuality;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import com.storeanalytics.metrics.service.StoreKpiResult;
import com.storeanalytics.metrics.service.StoreKpiService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class StoreKpiControllerTest {

    private StoreKpiService storeKpiService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        storeKpiService = mock(StoreKpiService.class);
        JsonMapper objectMapper = JsonMapper.builder()
                .findAndAddModules()
                .build();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new StoreKpiController(storeKpiService))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(objectMapper))
                .setControllerAdvice(
                        new ApiExceptionHandler()
                )
                .build();
    }

    @Test
    void returnsStoreKpiForExplicitInclusivePeriod() throws Exception {
        UUID storeId = UUID.randomUUID();
        when(storeKpiService.calculate(any(UUID.class), any(StoreKpiPeriod.class)))
                .thenReturn(result(storeId));

        mockMvc.perform(get("/api/stores/{storeId}/kpi", storeId)
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd", "2026-07-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value(storeId.toString()))
                .andExpect(jsonPath("$.periodStart").value("2026-07-01"))
                .andExpect(jsonPath("$.periodEnd").value("2026-07-31"))
                .andExpect(jsonPath("$.formulaVersion").value("store-kpi-v1"))
                .andExpect(jsonPath("$.netRevenue").value(300.00))
                .andExpect(jsonPath("$.netQuantity").value(3.000))
                .andExpect(jsonPath("$.grossProfit").value(100.00))
                .andExpect(jsonPath("$.marginPercent").value(33.33))
                .andExpect(jsonPath("$.dataQuality.completeCostData").value(true))
                .andExpect(jsonPath("$.dataQuality.periodOpenConsistencyIssueCount").value(0));

        ArgumentCaptor<StoreKpiPeriod> period =
                ArgumentCaptor.forClass(StoreKpiPeriod.class);
        verify(storeKpiService).calculate(eq(storeId), period.capture());
        assertThat(period.getValue().start()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(period.getValue().end()).isEqualTo(LocalDate.of(2026, 7, 31));
    }

    @Test
    void rejectsReversedPeriod() throws Exception {
        UUID storeId = UUID.randomUUID();

        mockMvc.perform(get("/api/stores/{storeId}/kpi", storeId)
                        .queryParam("periodStart", "2026-07-31")
                        .queryParam("periodEnd", "2026-07-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void rejectsMalformedDate() throws Exception {
        UUID storeId = UUID.randomUUID();

        mockMvc.perform(get("/api/stores/{storeId}/kpi", storeId)
                        .queryParam("periodStart", "not-a-date")
                        .queryParam("periodEnd", "2026-07-31"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void rejectsAnalyticsPeriodLongerThan366InclusiveDays() throws Exception {
        UUID storeId = UUID.randomUUID();

        mockMvc.perform(get("/api/stores/{storeId}/kpi", storeId)
                        .queryParam("periodStart", "2024-01-01")
                        .queryParam("periodEnd", "2025-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value("Request parameters are invalid"));
    }

    @Test
    void reportsUnknownStore() throws Exception {
        UUID storeId = UUID.randomUUID();
        when(storeKpiService.calculate(any(UUID.class), any(StoreKpiPeriod.class)))
                .thenThrow(new StoreNotFoundException(storeId));

        mockMvc.perform(get("/api/stores/{storeId}/kpi", storeId)
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd", "2026-07-31"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STORE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(
                        "Store was not found"
                ));
    }

    private StoreKpiResult result(UUID storeId) {
        return new StoreKpiResult(
                storeId,
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31),
                "store-kpi-v1",
                new BigDecimal("300.00"),
                new BigDecimal("3.000"),
                new BigDecimal("200.00"),
                new BigDecimal("100.00"),
                new BigDecimal("33.33"),
                new StoreKpiDataQuality(true, 2, 0, 0, 0, 0, 0)
        );
    }
}
