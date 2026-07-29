package com.storeanalytics.quality.web;
import com.storeanalytics.common.web.ApiExceptionHandler;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.json.JsonMapper;
import com.storeanalytics.quality.service.DataQualityHealthStatus;
import com.storeanalytics.quality.service.StorePeriodQualityService;
import com.storeanalytics.quality.service.StorePeriodQualityView;
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

class StorePeriodQualityControllerTest {

    private StorePeriodQualityService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(StorePeriodQualityService.class);
        JsonMapper objectMapper = JsonMapper.builder()
                .findAndAddModules()
                .build();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new StorePeriodQualityController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void returnsExplicitMonthlyCutoffContract() throws Exception {
        UUID storeId = UUID.randomUUID();
        YearMonth month = YearMonth.of(2026, 7);
        LocalDate asOf = LocalDate.of(2026, 7, 20);
        StorePeriodQualityView view = new StorePeriodQualityView(
                storeId,
                month.atDay(1),
                month.atDay(1),
                month.atEndOfMonth(),
                asOf,
                DataQualityHealthStatus.WARNING,
                true,
                List.of(),
                null,
                null,
                null,
                null,
                List.of(),
                Instant.parse("2026-07-20T12:00:00Z")
        );
        when(service.inspect(storeId, month, asOf)).thenReturn(view);

        mockMvc.perform(get(
                        "/api/stores/{storeId}/period-quality/{month}",
                        storeId,
                        "2026-07"
                ).queryParam("asOf", "2026-07-20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value(storeId.toString()))
                .andExpect(jsonPath("$.periodMonth").value("2026-07-01"))
                .andExpect(jsonPath("$.asOfDate").value("2026-07-20"))
                .andExpect(jsonPath("$.status").value("WARNING"))
                .andExpect(jsonPath("$.readyForDecisions").value(true));
        verify(service).inspect(storeId, month, asOf);
    }

    @Test
    void rejectsMalformedCutoff() throws Exception {
        mockMvc.perform(get(
                        "/api/stores/{storeId}/period-quality/{month}",
                        UUID.randomUUID(),
                        "2026-07"
                ).queryParam("asOf", "20-07-2026"))
                .andExpect(status().isBadRequest());
    }
}
