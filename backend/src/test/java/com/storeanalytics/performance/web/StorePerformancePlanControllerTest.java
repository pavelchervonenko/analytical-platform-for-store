package com.storeanalytics.performance.web;

import static org.mockito.ArgumentMatchers.any;
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
import com.storeanalytics.performance.exception.PerformancePlanNotFoundException;
import com.storeanalytics.performance.model.StorePlanTargets;
import com.storeanalytics.performance.service.StorePerformancePlanService;
import com.storeanalytics.performance.service.StorePerformancePlanView;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class StorePerformancePlanControllerTest {

    private StorePerformancePlanService planService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        planService = mock(StorePerformancePlanService.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new StorePerformancePlanController(planService))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(
                        new ApiExceptionHandler()
                )
                .build();
    }

    @Test
    void readsAndUpdatesMonthlyPlan() throws Exception {
        UUID storeId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        StorePerformancePlanView view = view(storeId, actorId);
        when(planService.get(storeId, YearMonth.of(2026, 7))).thenReturn(view);
        when(planService.upsert(
                eq(storeId),
                eq(YearMonth.of(2026, 7)),
                any(StorePlanTargets.class),
                eq(actorId)
        )).thenReturn(view);

        mockMvc.perform(get("/api/stores/{storeId}/performance-plans/{month}", storeId, "2026-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revenueTarget").value(24000000.00))
                .andExpect(jsonPath("$.additionalShareTarget").value(7.00));

        mockMvc.perform(put(
                        "/api/stores/{storeId}/performance-plans/{month}",
                        storeId,
                        "2026-07"
                ).principal(authentication(actorId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "revenueTarget": 24000000.00,
                                  "accessoryShareTarget": 3.90,
                                  "serviceShareTarget": 3.00,
                                  "additionalShareTarget": 7.00
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        verify(planService).upsert(
                eq(storeId),
                eq(YearMonth.of(2026, 7)),
                any(StorePlanTargets.class),
                eq(actorId)
        );
    }

    @Test
    void validatesPlanAndReportsMissingMonth() throws Exception {
        UUID storeId = UUID.randomUUID();
        when(planService.get(storeId, YearMonth.of(2026, 8)))
                .thenThrow(new PerformancePlanNotFoundException(
                        storeId, LocalDate.of(2026, 8, 1)
                ));

        mockMvc.perform(get("/api/stores/{storeId}/performance-plans/{month}", storeId, "2026-08"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PERFORMANCE_PLAN_NOT_FOUND"));

        mockMvc.perform(put(
                        "/api/stores/{storeId}/performance-plans/{month}",
                        storeId,
                        "2026-07"
                ).principal(authentication(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revenueTarget\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private TestingAuthenticationToken authentication(UUID actorId) {
        AppUserPrincipal principal = mock(AppUserPrincipal.class);
        when(principal.getUserId()).thenReturn(actorId);
        return new TestingAuthenticationToken(principal, null);
    }

    private StorePerformancePlanView view(UUID storeId, UUID actorId) {
        return new StorePerformancePlanView(
                UUID.randomUUID(),
                storeId,
                LocalDate.of(2026, 7, 1),
                new BigDecimal("24000000.00"),
                new BigDecimal("3.90"),
                new BigDecimal("3.00"),
                new BigDecimal("7.00"),
                actorId,
                2,
                Instant.parse("2026-07-21T10:00:00Z")
        );
    }
}
