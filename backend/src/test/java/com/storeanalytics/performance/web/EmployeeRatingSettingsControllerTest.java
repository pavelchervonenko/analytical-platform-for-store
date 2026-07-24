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
import com.storeanalytics.performance.exception.EmployeeAssignmentNotFoundException;
import com.storeanalytics.performance.service.EmployeeRatingSettingView;
import com.storeanalytics.performance.service.EmployeeRatingSettingsService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class EmployeeRatingSettingsControllerTest {

    private EmployeeRatingSettingsService settingsService;
    private MockMvc mockMvc;
    private UUID actorId;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        settingsService = mock(EmployeeRatingSettingsService.class);
        actorId = UUID.randomUUID();
        AppUserPrincipal principal = mock(AppUserPrincipal.class);
        when(principal.getUserId()).thenReturn(actorId);
        authentication = new TestingAuthenticationToken(principal, null);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new EmployeeRatingSettingsController(settingsService))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(
                        new ApiExceptionHandler()
                )
                .build();
    }

    @Test
    void listsAndUpdatesRatingParticipation() throws Exception {
        UUID storeId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        EmployeeRatingSettingView view = view(employeeId, true, 3);
        when(settingsService.findAll(storeId)).thenReturn(List.of(view));
        when(settingsService.updateParticipation(storeId, employeeId, true, 2, actorId))
                .thenReturn(view);

        mockMvc.perform(get("/api/stores/{storeId}/employee-rating-settings", storeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].employeeId").value(employeeId.toString()))
                .andExpect(jsonPath("$[0].participatesInRanking").value(true))
                .andExpect(jsonPath("$[0].version").value(3));

        mockMvc.perform(put(
                        "/api/stores/{storeId}/employee-rating-settings/{employeeId}",
                        storeId,
                        employeeId
                ).principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"participatesInRanking\":true,\"version\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participatesInRanking").value(true))
                .andExpect(jsonPath("$.version").value(3));

        verify(settingsService).updateParticipation(storeId, employeeId, true, 2, actorId);
    }

    @Test
    void validatesRequestAndReportsMissingAssignment() throws Exception {
        UUID storeId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();

        mockMvc.perform(put(
                        "/api/stores/{storeId}/employee-rating-settings/{employeeId}",
                        storeId,
                        employeeId
                ).principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"participatesInRanking\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        when(settingsService.updateParticipation(
                eq(storeId), eq(employeeId), eq(true), eq(0L), eq(actorId)
        )).thenThrow(new EmployeeAssignmentNotFoundException(storeId, employeeId));

        mockMvc.perform(put(
                        "/api/stores/{storeId}/employee-rating-settings/{employeeId}",
                        storeId,
                        employeeId
                ).principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"participatesInRanking\":true,\"version\":0}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EMPLOYEE_ASSIGNMENT_NOT_FOUND"));
    }

    private EmployeeRatingSettingView view(
            UUID employeeId,
            boolean participates,
            long version
    ) {
        return new EmployeeRatingSettingView(
                employeeId,
                "\u0410\u043d\u043d\u0430",
                true,
                true,
                participates,
                version,
                Instant.parse("2026-07-21T10:00:00Z")
        );
    }
}
