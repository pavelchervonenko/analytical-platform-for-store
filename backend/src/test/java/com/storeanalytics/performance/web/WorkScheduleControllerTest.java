package com.storeanalytics.performance.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeanalytics.auth.security.AppUserPrincipal;
import com.storeanalytics.common.web.ApiExceptionHandler;
import com.storeanalytics.common.exception.InvalidRequestException;
import com.storeanalytics.performance.service.EmployeeShiftView;
import com.storeanalytics.performance.service.WorkScheduleService;
import com.storeanalytics.performance.service.WorkShiftInput;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WorkScheduleControllerTest {

    private WorkScheduleService scheduleService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        scheduleService = mock(WorkScheduleService.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new WorkScheduleController(scheduleService))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(
                        new ApiExceptionHandler()
                )
                .build();
    }

    @Test
    void readsAndReplacesDailyRosterWithActualHours() throws Exception {
        UUID storeId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 7, 21);
        BigDecimal hours = new BigDecimal("6.50");
        EmployeeShiftView shift = new EmployeeShiftView(
                UUID.randomUUID(), employeeId, "Анна", date, hours, true, 1
        );
        List<WorkShiftInput> inputs = List.of(new WorkShiftInput(employeeId, hours));
        when(scheduleService.find(storeId, date, date)).thenReturn(List.of(shift));
        when(scheduleService.replaceDay(storeId, date, inputs, actorId))
                .thenReturn(List.of(shift));

        mockMvc.perform(get("/api/stores/{storeId}/work-schedule", storeId)
                        .queryParam("periodStart", date.toString())
                        .queryParam("periodEnd", date.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].workedHours").value(6.50));

        mockMvc.perform(put("/api/stores/{storeId}/work-schedule/{workDate}", storeId, date)
                        .principal(authentication(actorId))
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
                .andExpect(jsonPath("$[0].employeeId").value(employeeId.toString()))
                .andExpect(jsonPath("$[0].workedHours").value(6.50));

        verify(scheduleService).replaceDay(
                eq(storeId), eq(date), eq(inputs), eq(actorId)
        );
    }

    @Test
    void keepsLegacyEmployeeIdsAsFullShift() throws Exception {
        UUID storeId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 7, 21);
        List<WorkShiftInput> inputs = List.of(new WorkShiftInput(
                employeeId, new BigDecimal("11.00")
        ));
        when(scheduleService.replaceDay(storeId, date, inputs, actorId))
                .thenReturn(List.of());

        mockMvc.perform(put("/api/stores/{storeId}/work-schedule/{workDate}", storeId, date)
                        .principal(authentication(actorId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeIds\":[\"" + employeeId + "\"]}"))
                .andExpect(status().isOk());

        verify(scheduleService).replaceDay(storeId, date, inputs, actorId);
    }

    @Test
    void rejectsHoursOutsideStoreDay() throws Exception {
        UUID storeId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 7, 21);

        mockMvc.perform(put("/api/stores/{storeId}/work-schedule/{workDate}", storeId, date)
                        .principal(authentication(UUID.randomUUID()))
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
