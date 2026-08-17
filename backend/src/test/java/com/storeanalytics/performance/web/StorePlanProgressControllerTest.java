package com.storeanalytics.performance.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.json.JsonMapper;
import com.storeanalytics.common.web.ApiExceptionHandler;
import com.storeanalytics.performance.service.StorePerformancePlanView;
import com.storeanalytics.performance.service.StorePlanCriterionType;
import com.storeanalytics.performance.service.StorePlanDirectionCode;
import com.storeanalytics.performance.service.StorePlanDirectionView;
import com.storeanalytics.performance.service.StorePlanProgressDataQuality;
import com.storeanalytics.performance.service.StorePlanProgressService;
import com.storeanalytics.performance.service.StorePlanProgressStatus;
import com.storeanalytics.performance.service.StorePlanProgressView;
import com.storeanalytics.store.service.StoreDataFreshnessStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class StorePlanProgressControllerTest {

    private StorePlanProgressService progressService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        progressService = mock(StorePlanProgressService.class);
        JsonMapper objectMapper = JsonMapper.builder()
                .findAndAddModules()
                .build();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new StorePlanProgressController(progressService))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(objectMapper))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void returnsStableProgressContractForExplicitCutoff() throws Exception {
        UUID storeId = UUID.randomUUID();
        YearMonth month = YearMonth.of(2026, 7);
        LocalDate asOf = LocalDate.of(2026, 7, 20);
        when(progressService.calculate(storeId, month, asOf)).thenReturn(view(storeId, asOf));

        mockMvc.perform(get(
                        "/api/stores/{storeId}/performance-plans/{month}/progress",
                        storeId,
                        "2026-07"
                ).queryParam("asOf", "2026-07-20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.formulaVersion").value("store-plan-progress-v2"))
                .andExpect(jsonPath("$.asOfDate").value("2026-07-20"))
                .andExpect(jsonPath("$.directions[0].code").value("REVENUE"))
                .andExpect(jsonPath("$.directions[0].criterionType").value("AMOUNT"))
                .andExpect(jsonPath("$.directions[0].status").value("ON_TRACK"))
                .andExpect(jsonPath("$.dataQuality.completeThroughAsOf").value(true));

        verify(progressService).calculate(storeId, month, asOf);
    }

    @Test
    void requiresExplicitAsOfDate() throws Exception {
        mockMvc.perform(get(
                        "/api/stores/{storeId}/performance-plans/{month}/progress",
                        UUID.randomUUID(),
                        "2026-07"
                ))
                .andExpect(status().isBadRequest());
    }

    private StorePlanProgressView view(UUID storeId, LocalDate asOf) {
        Instant now = Instant.parse("2026-07-23T10:00:00Z");
        StorePerformancePlanView plan = new StorePerformancePlanView(
                UUID.randomUUID(),
                storeId,
                LocalDate.of(2026, 7, 1),
                new BigDecimal("24000000.00"),
                new BigDecimal("3.90"),
                new BigDecimal("3.00"),
                new BigDecimal("7.00"),
                UUID.randomUUID(),
                1,
                now
        );
        StorePlanDirectionView revenue = new StorePlanDirectionView(
                StorePlanDirectionCode.REVENUE,
                StorePlanCriterionType.AMOUNT,
                new BigDecimal("15840000.00"),
                new BigDecimal("24000000.00"),
                new BigDecimal("66.00"),
                new BigDecimal("792000.00"),
                new BigDecimal("15483870.97"),
                new BigDecimal("356129.03"),
                new BigDecimal("24552000.00"),
                new BigDecimal("102.30"),
                new BigDecimal("8160000.00"),
                new BigDecimal("741818.18"),
                null,
                null,
                null,
                new BigDecimal("66.00"),
                false,
                StorePlanProgressStatus.ON_TRACK
        );
        return new StorePlanProgressView(
                storeId,
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31),
                asOf,
                31,
                20,
                11,
                "store-plan-progress-v2",
                plan,
                new StorePlanProgressDataQuality(
                        StoreDataFreshnessStatus.CURRENT,
                        asOf,
                        true,
                        true,
                        0,
                        0
                ),
                0,
                false,
                List.of(),
                List.of(revenue),
                List.of(),
                now
        );
    }
}
