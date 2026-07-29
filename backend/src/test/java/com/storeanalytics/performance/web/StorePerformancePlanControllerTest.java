package com.storeanalytics.performance.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import com.storeanalytics.common.exception.PreconditionRequiredException;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class StorePerformancePlanControllerTest {

    private StorePerformancePlanService planService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        planService = mock(StorePerformancePlanService.class);
        JsonMapper objectMapper = JsonMapper.builder()
                .findAndAddModules()
                .build();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new StorePerformancePlanController(planService))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(objectMapper))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void readsAndUpdatesMonthlyPlanWithStrongEtag() throws Exception {
        UUID storeId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        StorePerformancePlanView view = view(storeId, actorId);
        String etag = StorePerformancePlanService.etag(view);
        when(planService.get(storeId, YearMonth.of(2026, 7))).thenReturn(view);
        when(planService.upsert(
                eq(storeId),
                eq(YearMonth.of(2026, 7)),
                any(StorePlanTargets.class),
                eq(etag),
                isNull(),
                eq(actorId)
        )).thenReturn(view);

        mockMvc.perform(get("/api/stores/{storeId}/performance-plans/{month}", storeId, "2026-07"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, etag))
                .andExpect(jsonPath("$.revenueTarget").value(24000000.00));

        mockMvc.perform(put(
                        "/api/stores/{storeId}/performance-plans/{month}",
                        storeId,
                        "2026-07"
                ).principal(authentication(actorId))
                        .header(HttpHeaders.IF_MATCH, etag)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planBody()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, etag))
                .andExpect(jsonPath("$.version").value(2));

        verify(planService).upsert(
                eq(storeId),
                eq(YearMonth.of(2026, 7)),
                any(StorePlanTargets.class),
                eq(etag),
                isNull(),
                eq(actorId)
        );
    }

    @Test
    void createsMissingPlanOnlyWithIfNoneMatch() throws Exception {
        UUID storeId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        StorePerformancePlanView view = view(storeId, actorId);
        when(planService.upsert(
                eq(storeId),
                eq(YearMonth.of(2026, 7)),
                any(StorePlanTargets.class),
                isNull(),
                eq("*"),
                eq(actorId)
        )).thenReturn(view);

        mockMvc.perform(put(
                        "/api/stores/{storeId}/performance-plans/{month}",
                        storeId,
                        "2026-07"
                ).principal(authentication(actorId))
                        .header(HttpHeaders.IF_NONE_MATCH, "*")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planBody()))
                .andExpect(status().isOk());
    }

    @Test
    void returnsStableErrorWhenMutationHasNoPrecondition() throws Exception {
        UUID storeId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        when(planService.upsert(
                eq(storeId),
                eq(YearMonth.of(2026, 7)),
                any(StorePlanTargets.class),
                isNull(),
                isNull(),
                eq(actorId)
        )).thenThrow(new PreconditionRequiredException("missing"));

        mockMvc.perform(put(
                        "/api/stores/{storeId}/performance-plans/{month}",
                        storeId,
                        "2026-07"
                ).principal(authentication(actorId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planBody()))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
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
                        .header(HttpHeaders.IF_NONE_MATCH, "*")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revenueTarget\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private String planBody() {
        return """
                {
                  "revenueTarget": 24000000.00,
                  "accessoryShareTarget": 3.90,
                  "serviceShareTarget": 3.00,
                  "additionalShareTarget": 7.00
                }
                """;
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
