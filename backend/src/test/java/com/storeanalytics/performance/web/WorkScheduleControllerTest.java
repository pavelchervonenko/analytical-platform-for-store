package com.storeanalytics.performance.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.json.JsonMapper;
import com.storeanalytics.auth.security.AppUserPrincipal;
import com.storeanalytics.common.exception.InvalidRequestException;
import com.storeanalytics.common.exception.PreconditionRequiredException;
import com.storeanalytics.common.web.ApiExceptionHandler;
import com.storeanalytics.performance.service.EmployeeShiftView;
import com.storeanalytics.performance.service.WorkScheduleDayView;
import com.storeanalytics.performance.service.WorkScheduleService;
import com.storeanalytics.performance.service.WorkShiftInput;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WorkScheduleControllerTest {

    private WorkScheduleService scheduleService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        scheduleService = mock(WorkScheduleService.class);
        JsonMapper objectMapper = JsonMapper.builder()
                .findAndAddModules()
                .build();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new WorkScheduleController(scheduleService))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(objectMapper))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void readsRangeAndReplacesDailyAggregateWithEtag() throws Exception {
        UUID storeId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 7, 21);
        BigDecimal hours = new BigDecimal("6.50");
        EmployeeShiftView shift = new EmployeeShiftView(
                UUID.randomUUID(), employeeId, "Анна", date, hours, true, 1
        );
        WorkScheduleDayView current = new WorkScheduleDayView(
                storeId, date, 4, List.of(shift)
        );
        WorkScheduleDayView saved = new WorkScheduleDayView(
                storeId, date, 5, List.of(shift)
        );
        String currentEtag = WorkScheduleService.etag(storeId, date, 4);
        String savedEtag = WorkScheduleService.etag(storeId, date, 5);
        List<WorkShiftInput> inputs = List.of(new WorkShiftInput(employeeId, hours));
        when(scheduleService.find(storeId, date, date)).thenReturn(List.of(shift));
        when(scheduleService.getDay(storeId, date)).thenReturn(current);
        when(scheduleService.replaceDay(storeId, date, inputs, currentEtag, actorId))
                .thenReturn(saved);

        mockMvc.perform(get("/api/stores/{storeId}/work-schedule", storeId)
                        .queryParam("periodStart", date.toString())
                        .queryParam("periodEnd", date.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].workedHours").value(6.50));

        mockMvc.perform(get("/api/stores/{storeId}/work-schedule/{workDate}", storeId, date))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, currentEtag))
                .andExpect(jsonPath("$.revision").value(4))
                .andExpect(jsonPath("$.shifts[0].employeeId").value(employeeId.toString()));

        mockMvc.perform(put("/api/stores/{storeId}/work-schedule/{workDate}", storeId, date)
                        .principal(authentication(actorId))
                        .header(HttpHeaders.IF_MATCH, currentEtag)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "shifts": [{
                                    "employeeId": "%s",
                                    "workedHours": 6.50
                                  }]
                                }
                                """.formatted(employeeId)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, savedEtag))
                .andExpect(jsonPath("$.revision").value(5))
                .andExpect(jsonPath("$.shifts[0].workedHours").value(6.50));

        verify(scheduleService).replaceDay(
                eq(storeId), eq(date), eq(inputs), eq(currentEtag), eq(actorId)
        );
    }

    @Test
    void keepsLegacyEmployeeIdsAsFullShift() throws Exception {
        UUID storeId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 7, 21);
        String etag = WorkScheduleService.etag(storeId, date, 0);
        List<WorkShiftInput> inputs = List.of(new WorkShiftInput(
                employeeId, new BigDecimal("11.00")
        ));
        when(scheduleService.replaceDay(storeId, date, inputs, etag, actorId))
                .thenReturn(new WorkScheduleDayView(storeId, date, 1, List.of()));

        mockMvc.perform(put("/api/stores/{storeId}/work-schedule/{workDate}", storeId, date)
                        .principal(authentication(actorId))
                        .header(HttpHeaders.IF_MATCH, etag)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeIds\":[\"" + employeeId + "\"]}"))
                .andExpect(status().isOk());

        verify(scheduleService).replaceDay(storeId, date, inputs, etag, actorId);
    }

    @Test
    void returnsStableErrorWhenPreconditionIsMissing() throws Exception {
        UUID storeId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 7, 21);
        when(scheduleService.replaceDay(storeId, date, List.of(), null, actorId))
                .thenThrow(new PreconditionRequiredException("missing"));

        mockMvc.perform(put("/api/stores/{storeId}/work-schedule/{workDate}", storeId, date)
                        .principal(authentication(actorId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shifts\":[]}"))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
    }

    @Test
    void rejectsHoursOutsideStoreDay() throws Exception {
        UUID storeId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 7, 21);

        mockMvc.perform(put("/api/stores/{storeId}/work-schedule/{workDate}", storeId, date)
                        .principal(authentication(UUID.randomUUID()))
                        .header(
                                HttpHeaders.IF_MATCH,
                                WorkScheduleService.etag(storeId, date, 0)
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "shifts": [{
                                    "employeeId": "%s",
                                    "workedHours": 11.01
                                  }]
                                }
                                """.formatted(employeeId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void reportsInvalidSchedulePeriod() throws Exception {
        UUID storeId = UUID.randomUUID();
        LocalDate start = LocalDate.of(2026, 7, 31);
        LocalDate end = LocalDate.of(2026, 7, 1);
        when(scheduleService.find(storeId, start, end))
                .thenThrow(new InvalidRequestException("invalid period"));

        mockMvc.perform(get("/api/stores/{storeId}/work-schedule", storeId)
                        .queryParam("periodStart", start.toString())
                        .queryParam("periodEnd", end.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    private TestingAuthenticationToken authentication(UUID actorId) {
        AppUserPrincipal principal = mock(AppUserPrincipal.class);
        when(principal.getUserId()).thenReturn(actorId);
        return new TestingAuthenticationToken(principal, null);
    }
}
