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
import com.storeanalytics.metrics.service.AverageKpiResult;
import com.storeanalytics.metrics.service.AverageKpiService;
import com.storeanalytics.metrics.service.AverageMetricComparison;
import com.storeanalytics.metrics.service.AverageMetricSnapshot;
import com.storeanalytics.metrics.service.CategoryAverageEntry;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
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

class AverageKpiControllerTest {

    private AverageKpiService averageKpiService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        averageKpiService = mock(AverageKpiService.class);
        JsonMapper objectMapper = JsonMapper.builder()
                .findAndAddModules()
                .build();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AverageKpiController(averageKpiService))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(objectMapper))
                .setControllerAdvice(
                        new ApiExceptionHandler()
                )
                .build();
    }

    @Test
    void returnsAveragesForExplicitInclusivePeriod() throws Exception {
        UUID storeId = UUID.randomUUID();
        when(averageKpiService.calculate(any(UUID.class), any(StoreKpiPeriod.class)))
                .thenReturn(result(storeId));

        mockMvc.perform(get("/api/stores/{storeId}/kpi/averages", storeId)
                        .queryParam("periodStart", "2026-07-10")
                        .queryParam("periodEnd", "2026-07-12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value(storeId.toString()))
                .andExpect(jsonPath("$.periodStart").value("2026-07-10"))
                .andExpect(jsonPath("$.periodEnd").value("2026-07-12"))
                .andExpect(jsonPath("$.previousPeriodStart").value("2026-07-07"))
                .andExpect(jsonPath("$.previousPeriodEnd").value("2026-07-09"))
                .andExpect(jsonPath("$.formulaVersion").value("average-kpi-v1"))
                .andExpect(jsonPath("$.averageReceipt.current.numerator").value(1000.00))
                .andExpect(jsonPath("$.averageReceipt.current.denominator").value(3))
                .andExpect(jsonPath("$.averageReceipt.current.value").value(333))
                .andExpect(jsonPath("$.averageReceipt.changePercent").value(11.1))
                .andExpect(jsonPath("$.additionalRevenuePerPhone.current.value").value(250))
                .andExpect(jsonPath("$.categoryAveragePrices[0].categoryCode")
                        .value("CHARGER_CABLE"))
                .andExpect(jsonPath("$.categoryAveragePrices[0].averageUnitPrice.current.value")
                        .value(100));

        ArgumentCaptor<StoreKpiPeriod> period =
                ArgumentCaptor.forClass(StoreKpiPeriod.class);
        verify(averageKpiService).calculate(eq(storeId), period.capture());
        assertThat(period.getValue().start()).isEqualTo(LocalDate.of(2026, 7, 10));
        assertThat(period.getValue().end()).isEqualTo(LocalDate.of(2026, 7, 12));
    }

    @Test
    void rejectsReversedPeriod() throws Exception {
        UUID storeId = UUID.randomUUID();

        mockMvc.perform(get("/api/stores/{storeId}/kpi/averages", storeId)
                        .queryParam("periodStart", "2026-07-12")
                        .queryParam("periodEnd", "2026-07-10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void rejectsMalformedDate() throws Exception {
        UUID storeId = UUID.randomUUID();

        mockMvc.perform(get("/api/stores/{storeId}/kpi/averages", storeId)
                        .queryParam("periodStart", "invalid")
                        .queryParam("periodEnd", "2026-07-12"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void reportsUnknownStore() throws Exception {
        UUID storeId = UUID.randomUUID();
        when(averageKpiService.calculate(any(UUID.class), any(StoreKpiPeriod.class)))
                .thenThrow(new StoreNotFoundException(storeId));

        mockMvc.perform(get("/api/stores/{storeId}/kpi/averages", storeId)
                        .queryParam("periodStart", "2026-07-10")
                        .queryParam("periodEnd", "2026-07-12"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STORE_NOT_FOUND"));
    }

    private AverageKpiResult result(UUID storeId) {
        return new AverageKpiResult(
                storeId,
                LocalDate.of(2026, 7, 10),
                LocalDate.of(2026, 7, 12),
                LocalDate.of(2026, 7, 7),
                LocalDate.of(2026, 7, 9),
                "average-kpi-v1",
                comparison("1000.00", "3", "333", "600.00", "2", "300", "11.1"),
                comparison("500.00", "2.000", "250", "200.00", "1.000", "200", "25.0"),
                List.of(new CategoryAverageEntry(
                        "CHARGER_CABLE",
                        "CHARGER_CABLE",
                        true,
                        comparison(
                                "100.00", "1.000", "100", "90.00", "1.000", "90", "11.1"
                        )
                ))
        );
    }

    private AverageMetricComparison comparison(
            String currentNumerator,
            String currentDenominator,
            String currentValue,
            String previousNumerator,
            String previousDenominator,
            String previousValue,
            String changePercent
    ) {
        return new AverageMetricComparison(
                new AverageMetricSnapshot(
                        decimal(currentNumerator),
                        decimal(currentDenominator),
                        decimal(currentValue)
                ),
                new AverageMetricSnapshot(
                        decimal(previousNumerator),
                        decimal(previousDenominator),
                        decimal(previousValue)
                ),
                decimal(changePercent)
        );
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
