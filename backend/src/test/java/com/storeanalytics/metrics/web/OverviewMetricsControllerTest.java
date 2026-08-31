package com.storeanalytics.metrics.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storeanalytics.common.web.ApiExceptionHandler;
import com.storeanalytics.metrics.service.OverviewCommercialMetric;
import com.storeanalytics.metrics.service.OverviewMetricScope;
import com.storeanalytics.metrics.service.OverviewMetricsDataQuality;
import com.storeanalytics.metrics.service.OverviewMetricsResult;
import com.storeanalytics.metrics.service.OverviewMetricsService;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class OverviewMetricsControllerTest {

    private OverviewMetricsService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(OverviewMetricsService.class);
        JsonMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new OverviewMetricsController(service))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(objectMapper))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void defaultsToSellerScope() throws Exception {
        UUID storeId = UUID.randomUUID();
        StoreKpiPeriod period = new StoreKpiPeriod(
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 28)
        );
        when(service.calculate(storeId, period, OverviewMetricScope.SELLERS))
                .thenReturn(result(storeId, period, OverviewMetricScope.SELLERS));

        mockMvc.perform(get("/api/stores/{storeId}/overview-metrics", storeId)
                        .queryParam("periodStart", "2026-08-01")
                        .queryParam("periodEnd", "2026-08-28"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("SELLERS"))
                .andExpect(jsonPath("$.formulaVersion").value("overview-metrics-v1"))
                .andExpect(jsonPath("$.additional.sharePercent").value(15.0))
                .andExpect(jsonPath("$.dataQuality.reconciliationPassed").value(true));

        verify(service).calculate(storeId, period, OverviewMetricScope.SELLERS);
    }

    @Test
    void acceptsExplicitStoreScope() throws Exception {
        UUID storeId = UUID.randomUUID();
        StoreKpiPeriod period = new StoreKpiPeriod(
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 28)
        );
        when(service.calculate(storeId, period, OverviewMetricScope.STORE))
                .thenReturn(result(storeId, period, OverviewMetricScope.STORE));

        mockMvc.perform(get("/api/stores/{storeId}/overview-metrics", storeId)
                        .queryParam("periodStart", "2026-08-01")
                        .queryParam("periodEnd", "2026-08-28")
                        .queryParam("scope", "STORE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("STORE"));
    }

    private OverviewMetricsResult result(
            UUID storeId,
            StoreKpiPeriod period,
            OverviewMetricScope scope
    ) {
        OverviewCommercialMetric additional = new OverviewCommercialMetric(
                new BigDecimal("150.00"),
                new BigDecimal("2.000"),
                new BigDecimal("15.00")
        );
        return new OverviewMetricsResult(
                storeId,
                period.start(),
                period.end(),
                scope,
                "overview-metrics-v1",
                new BigDecimal("1000.00"),
                new BigDecimal("10.000"),
                new BigDecimal("600.00"),
                new BigDecimal("400.00"),
                new BigDecimal("40.00"),
                additional,
                new OverviewCommercialMetric(
                        new BigDecimal("100.00"),
                        BigDecimal.ONE,
                        new BigDecimal("10.00")
                ),
                new OverviewCommercialMetric(
                        new BigDecimal("50.00"),
                        BigDecimal.ONE,
                        new BigDecimal("5.00")
                ),
                List.of(),
                new OverviewMetricsDataQuality(true, 2, 0, 0, 0, 0, 0, true)
        );
    }
}
