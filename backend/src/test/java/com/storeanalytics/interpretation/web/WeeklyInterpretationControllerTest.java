package com.storeanalytics.interpretation.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storeanalytics.common.web.ApiExceptionHandler;
import com.storeanalytics.common.web.PageResponse;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.QualityStatus;
import com.storeanalytics.interpretation.exception.WeeklyInterpretationNotFoundException;
import com.storeanalytics.interpretation.generation.LlmAnalysisTriggerType;
import com.storeanalytics.interpretation.query.WeeklyInterpretationDetailView;
import com.storeanalytics.interpretation.query.WeeklyInterpretationEmployeeView;
import com.storeanalytics.interpretation.query.WeeklyInterpretationQueryService;
import com.storeanalytics.interpretation.query.WeeklyInterpretationSummaryView;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class WeeklyInterpretationControllerTest {

    private WeeklyInterpretationQueryService service;
    private MockMvc mockMvc;
    private JsonMapper objectMapper;

    @BeforeEach
    void setUp() {
        service = mock(WeeklyInterpretationQueryService.class);
        objectMapper = JsonMapper.builder().findAndAddModules().build();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new WeeklyInterpretationController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void listsOnlyCurrentWeeklyRevisionsUsingStableEnvelope() throws Exception {
        UUID storeId = UUID.randomUUID();
        WeeklyInterpretationSummaryView summary = summary(storeId);
        when(service.list(storeId, null, null, 0, 12)).thenReturn(
                new PageResponse<>(List.of(summary), 0, 12, 1, 1, false, false)
        );

        mockMvc.perform(get(
                        "/api/stores/{storeId}/interpretations/weekly",
                        storeId
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id")
                        .value(summary.id().toString()))
                .andExpect(jsonPath("$.items[0].interpretationRevision").value(2))
                .andExpect(jsonPath("$.items[0].currentRevision").value(true))
                .andExpect(jsonPath("$.items[0].contentHash")
                        .value("a".repeat(64)))
                .andExpect(jsonPath("$.items[0].contentSchemaVersion").value(1))
                .andExpect(jsonPath("$.items[0].qualityStatus").value("READY"))
                .andExpect(jsonPath("$.items[0].employeeCount").value(1))
                .andExpect(jsonPath("$.totalElements").value(1));
        verify(service).list(storeId, null, null, 0, 12);
    }

    @Test
    void returnsLatestContentAndImmutableEmployeeDirectory() throws Exception {
        UUID storeId = UUID.randomUUID();
        WeeklyInterpretationSummaryView summary = summary(storeId);
        UUID employeeId = UUID.randomUUID();
        WeeklyInterpretationDetailView detail = new WeeklyInterpretationDetailView(
                summary,
                objectMapper.readTree("{\"store\":{\"headline\":{}}}"),
                List.of(new WeeklyInterpretationEmployeeView(
                        "E01", employeeId, "Ирина"
                ))
        );
        when(service.latest(storeId)).thenReturn(detail);

        mockMvc.perform(get(
                        "/api/stores/{storeId}/interpretations/weekly/latest",
                        storeId
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interpretation.id")
                        .value(summary.id().toString()))
                .andExpect(jsonPath("$.content.store.headline").isMap())
                .andExpect(jsonPath("$.employees[0].employeeRef").value("E01"))
                .andExpect(jsonPath("$.employees[0].employeeId")
                        .value(employeeId.toString()))
                .andExpect(jsonPath("$.employees[0].displayName").value("Ирина"));
    }

    @Test
    void returnsStableNotFoundResponse() throws Exception {
        UUID storeId = UUID.randomUUID();
        when(service.latest(storeId)).thenThrow(
                WeeklyInterpretationNotFoundException.latest()
        );

        mockMvc.perform(get(
                        "/api/stores/{storeId}/interpretations/weekly/latest",
                        storeId
                ))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("WEEKLY_INTERPRETATION_NOT_FOUND"))
                .andExpect(jsonPath("$.message")
                        .value("Weekly interpretation was not found"));
    }

    @Test
    void rejectsMalformedHistoryDate() throws Exception {
        UUID storeId = UUID.randomUUID();

        mockMvc.perform(get(
                        "/api/stores/{storeId}/interpretations/weekly",
                        storeId
                ).queryParam("periodStartFrom", "not-a-date"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    private WeeklyInterpretationSummaryView summary(UUID storeId) {
        return new WeeklyInterpretationSummaryView(
                UUID.randomUUID(),
                storeId,
                UUID.randomUUID(),
                LocalDate.of(2026, 7, 27),
                LocalDate.of(2026, 8, 2),
                "Europe/Moscow",
                2,
                2,
                true,
                UUID.randomUUID(),
                LlmAnalysisTriggerType.MANUAL_REGENERATION,
                "a".repeat(64),
                1,
                QualityStatus.READY,
                1,
                Instant.parse("2026-08-03T04:59:00Z"),
                Instant.parse("2026-08-03T05:00:00Z")
        );
    }
}
