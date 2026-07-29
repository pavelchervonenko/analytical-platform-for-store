package com.storeanalytics.report.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.json.JsonMapper;
import com.storeanalytics.common.web.ApiExceptionHandler;
import com.storeanalytics.common.web.PageResponse;
import com.storeanalytics.metrics.model.ReportStatus;
import com.storeanalytics.metrics.model.ReportType;
import com.storeanalytics.report.exception.ReportNotFoundException;
import com.storeanalytics.report.service.ReportActorView;
import com.storeanalytics.report.service.ReportCoverageStatus;
import com.storeanalytics.report.service.ReportQueryService;
import com.storeanalytics.report.service.ReportSummaryView;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ReportControllerTest {

    private ReportQueryService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(ReportQueryService.class);
        JsonMapper objectMapper = JsonMapper.builder()
                .findAndAddModules()
                .build();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ReportController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void listsFinalizedReportRevisionsUsingStableContract() throws Exception {
        UUID storeId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        ReportSummaryView report = new ReportSummaryView(
                reportId,
                storeId,
                ReportType.MONTHLY,
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31),
                ReportCoverageStatus.COMPLETE,
                ReportStatus.FINALIZED,
                2,
                true,
                UUID.randomUUID(),
                "Corrected payroll",
                UUID.randomUUID(),
                "store-monthly-report-v1",
                1,
                Instant.parse("2026-08-02T10:00:00Z"),
                new ReportActorView(UUID.randomUUID(), "Manager")
        );
        when(service.list(storeId, 2026, ReportType.MONTHLY, 0, 20))
                .thenReturn(new PageResponse<>(List.of(report), 0, 20, 1, 1, false, false));

        mockMvc.perform(get("/api/stores/{storeId}/reports", storeId)
                        .queryParam("year", "2026")
                        .queryParam("type", "MONTHLY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(reportId.toString()))
                .andExpect(jsonPath("$.items[0].type").value("MONTHLY"))
                .andExpect(jsonPath("$.items[0].status").value("FINALIZED"))
                .andExpect(jsonPath("$.items[0].revision").value(2))
                .andExpect(jsonPath("$.items[0].currentRevision").value(true))
                .andExpect(jsonPath("$.items[0].revisionReason").value("Corrected payroll"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.hasNext").value(false));
        verify(service).list(storeId, 2026, ReportType.MONTHLY, 0, 20);
    }

    @Test
    void returnsStableNotFoundErrorWithoutInternalMessage() throws Exception {
        UUID storeId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        when(service.get(storeId, reportId)).thenThrow(new ReportNotFoundException(reportId));

        mockMvc.perform(get(
                        "/api/stores/{storeId}/reports/{reportId}",
                        storeId,
                        reportId
                ))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REPORT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Report was not found"));
    }
}
