package com.storeanalytics.interpretation.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storeanalytics.interpretation.query.WeeklyInsightPeriodView;
import com.storeanalytics.interpretation.query.WeeklyInsightQueryService;
import com.storeanalytics.interpretation.query.WeeklyInsightReasonCode;
import com.storeanalytics.interpretation.query.WeeklyInsightResponse;
import com.storeanalytics.interpretation.query.WeeklyInsightState;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class WeeklyInsightControllerTest {

    private WeeklyInsightQueryService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(WeeklyInsightQueryService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new WeeklyInsightController(service))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(
                        JsonMapper.builder().findAndAddModules().build()
                ))
                .build();
    }

    @Test
    void returnsBusinessAvailabilityWithPrivateNoStoreCaching() throws Exception {
        UUID storeId = UUID.randomUUID();
        when(service.current(storeId)).thenReturn(new WeeklyInsightResponse(
                new WeeklyInsightPeriodView(
                        LocalDate.of(2026, 7, 27),
                        LocalDate.of(2026, 8, 2),
                        "Europe/Moscow"
                ),
                WeeklyInsightState.PREPARING,
                WeeklyInsightReasonCode.ANALYSIS_IN_PROGRESS,
                "Анализируем результаты недели.",
                Instant.parse("2026-08-03T06:00:00Z"),
                Instant.parse("2026-08-03T06:00:15Z"),
                null,
                null,
                null,
                Instant.parse("2026-08-03T05:55:00Z"),
                null,
                null,
                null
        ));

        mockMvc.perform(get(
                        "/api/stores/{storeId}/insights/weekly/current",
                        storeId
                ))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL, "private, no-store"
                ))
                .andExpect(jsonPath("$.state").value("PREPARING"))
                .andExpect(jsonPath("$.reasonCode")
                        .value("ANALYSIS_IN_PROGRESS"))
                .andExpect(jsonPath("$.period.periodStart")
                        .value("2026-07-27"))
                .andExpect(jsonPath("$.content").doesNotExist());
    }
}
