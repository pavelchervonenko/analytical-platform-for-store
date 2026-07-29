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
import com.storeanalytics.metrics.service.AttachRateDataQuality;
import com.storeanalytics.metrics.service.AttachRateEntry;
import com.storeanalytics.metrics.service.AttachRateResult;
import com.storeanalytics.metrics.service.AttachRateService;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import com.storeanalytics.product.model.AttachDenominatorCode;
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

class AttachRateControllerTest {

    private AttachRateService attachRateService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        attachRateService = mock(AttachRateService.class);
        JsonMapper objectMapper = JsonMapper.builder()
                .findAndAddModules()
                .build();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AttachRateController(attachRateService))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(objectMapper))
                .setControllerAdvice(
                        new ApiExceptionHandler()
                )
                .build();
    }

    @Test
    void returnsAttachRatesForExplicitInclusivePeriod() throws Exception {
        UUID storeId = UUID.randomUUID();
        when(attachRateService.calculate(any(UUID.class), any(StoreKpiPeriod.class)))
                .thenReturn(result(storeId));

        mockMvc.perform(get("/api/stores/{storeId}/kpi/attach-rates", storeId)
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd", "2026-07-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value(storeId.toString()))
                .andExpect(jsonPath("$.periodStart").value("2026-07-01"))
                .andExpect(jsonPath("$.periodEnd").value("2026-07-31"))
                .andExpect(jsonPath("$.formulaVersion").value("attach-rate-v1"))
                .andExpect(jsonPath("$.dataQuality.unmatchedNumeratorItemCount").value(1))
                .andExpect(jsonPath("$.rates[0].metricCode").value("CASE_APPLE_IPHONE"))
                .andExpect(jsonPath("$.rates[0].denominatorCode").value("IPHONE"))
                .andExpect(jsonPath("$.rates[0].numeratorQuantity").value(2.000))
                .andExpect(jsonPath("$.rates[0].denominatorQuantity").value(3.000))
                .andExpect(jsonPath("$.rates[0].ratePerHundred").value(66.7));

        ArgumentCaptor<StoreKpiPeriod> period =
                ArgumentCaptor.forClass(StoreKpiPeriod.class);
        verify(attachRateService).calculate(eq(storeId), period.capture());
        assertThat(period.getValue().start()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(period.getValue().end()).isEqualTo(LocalDate.of(2026, 7, 31));
    }

    @Test
    void rejectsReversedPeriod() throws Exception {
        UUID storeId = UUID.randomUUID();

        mockMvc.perform(get("/api/stores/{storeId}/kpi/attach-rates", storeId)
                        .queryParam("periodStart", "2026-07-31")
                        .queryParam("periodEnd", "2026-07-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void rejectsMalformedDate() throws Exception {
        UUID storeId = UUID.randomUUID();

        mockMvc.perform(get("/api/stores/{storeId}/kpi/attach-rates", storeId)
                        .queryParam("periodStart", "invalid")
                        .queryParam("periodEnd", "2026-07-31"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void reportsUnknownStore() throws Exception {
        UUID storeId = UUID.randomUUID();
        when(attachRateService.calculate(any(UUID.class), any(StoreKpiPeriod.class)))
                .thenThrow(new StoreNotFoundException(storeId));

        mockMvc.perform(get("/api/stores/{storeId}/kpi/attach-rates", storeId)
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd", "2026-07-31"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STORE_NOT_FOUND"));
    }

    private AttachRateResult result(UUID storeId) {
        return new AttachRateResult(
                storeId,
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31),
                "attach-rate-v1",
                new AttachRateDataQuality(1, 0, 0),
                List.of(new AttachRateEntry(
                        "CASE_APPLE_IPHONE",
                        "CASE_APPLE_IPHONE",
                        AttachDenominatorCode.IPHONE,
                        new BigDecimal("2.000"),
                        new BigDecimal("3.000"),
                        new BigDecimal("66.7")
                ))
        );
    }
}
