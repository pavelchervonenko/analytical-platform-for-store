package com.storeanalytics.performance.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.json.JsonMapper;
import com.storeanalytics.auth.security.AppUserPrincipal;
import com.storeanalytics.common.web.ApiExceptionHandler;
import com.storeanalytics.metrics.exception.StoreNotFoundException;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import com.storeanalytics.performance.service.EmployeeRatingFinalizationService;
import com.storeanalytics.performance.service.EmployeeRatingHistoryView;
import com.storeanalytics.performance.service.EmployeeRatingQueryService;
import com.storeanalytics.performance.service.EmployeeRatingResult;
import com.storeanalytics.performance.service.RatingFormulaView;
import com.storeanalytics.performance.service.RatingPlanContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class EmployeeRatingControllerTest {

    private EmployeeRatingQueryService ratingService;
    private EmployeeRatingFinalizationService finalizationService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ratingService = mock(EmployeeRatingQueryService.class);
        finalizationService = mock(EmployeeRatingFinalizationService.class);
        JsonMapper objectMapper = JsonMapper.builder()
                .findAndAddModules()
                .build();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new EmployeeRatingController(
                        ratingService, finalizationService
                ))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(objectMapper))
                .setControllerAdvice(
                        new ApiExceptionHandler()
                )
                .build();
    }

    @Test
    void returnsVersionedLiveRatingForExplicitPeriod() throws Exception {
        UUID storeId = UUID.randomUUID();
        when(ratingService.get(eq(storeId), any(StoreKpiPeriod.class)))
                .thenReturn(result(storeId));

        mockMvc.perform(get("/api/stores/{storeId}/employee-ratings", storeId)
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd", "2026-07-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value(storeId.toString()))
                .andExpect(jsonPath("$.formula.version").value("employee-rating-v1"))
                .andExpect(jsonPath("$.formula.contributionWeight").value(25.00))
                .andExpect(jsonPath("$.plan.proratedRevenueTarget").value(1000000.00))
                .andExpect(jsonPath("$.plan.revenueAchievementPercent").value(90.00))
                .andExpect(jsonPath("$.history.status").value("LIVE"))
                .andExpect(jsonPath("$.history.snapshotId").doesNotExist())
                .andExpect(jsonPath("$.employees").isEmpty());
    }

    @Test
    void finalizesRatingWithAuthenticatedActor() throws Exception {
        UUID storeId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        Authentication authentication = mock(Authentication.class);
        AppUserPrincipal principal = mock(AppUserPrincipal.class);
        when(authentication.getPrincipal()).thenReturn(principal);
        when(principal.getUserId()).thenReturn(userId);
        EmployeeRatingResult finalized = result(storeId).withHistory(
                EmployeeRatingHistoryView.finalized(
                        snapshotId,
                        Instant.parse("2026-08-01T07:00:00Z"),
                        userId,
                        "Manager"
                )
        );
        when(finalizationService.finalizePeriod(
                eq(storeId), any(StoreKpiPeriod.class), eq(userId)
        )).thenReturn(finalized);

        mockMvc.perform(post("/api/stores/{storeId}/employee-ratings/finalize", storeId)
                        .principal(authentication)
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd", "2026-07-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history.status").value("FINALIZED"))
                .andExpect(jsonPath("$.history.snapshotId").value(snapshotId.toString()))
                .andExpect(jsonPath("$.history.finalizedBy").value(userId.toString()))
                .andExpect(jsonPath("$.history.finalizedByName").value("Manager"));
    }

    @Test
    void rejectsReversedOrMalformedPeriod() throws Exception {
        UUID storeId = UUID.randomUUID();

        mockMvc.perform(get("/api/stores/{storeId}/employee-ratings", storeId)
                        .queryParam("periodStart", "2026-07-31")
                        .queryParam("periodEnd", "2026-07-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        mockMvc.perform(get("/api/stores/{storeId}/employee-ratings", storeId)
                        .queryParam("periodStart", "invalid")
                        .queryParam("periodEnd", "2026-07-31"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void reportsUnknownStore() throws Exception {
        UUID storeId = UUID.randomUUID();
        when(ratingService.get(eq(storeId), any(StoreKpiPeriod.class)))
                .thenThrow(new StoreNotFoundException(storeId));

        mockMvc.perform(get("/api/stores/{storeId}/employee-ratings", storeId)
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd", "2026-07-31"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STORE_NOT_FOUND"));
    }

    private EmployeeRatingResult result(UUID storeId) {
        BigDecimal twentyFive = new BigDecimal("25.00");
        return new EmployeeRatingResult(
                storeId,
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31),
                new RatingFormulaView(
                        "employee-rating-v1",
                        twentyFive,
                        twentyFive,
                        twentyFive,
                        twentyFive,
                        new BigDecimal("50.00"),
                        new BigDecimal("50.00"),
                        new BigDecimal("3.000"),
                        new BigDecimal("150.00"),
                        new BigDecimal("75.00")
                ),
                new RatingPlanContext(
                        true,
                        new BigDecimal("100.00"),
                        new BigDecimal("1000000.00"),
                        new BigDecimal("4.00"),
                        new BigDecimal("3.00"),
                        new BigDecimal("7.00"),
                        new BigDecimal("900000.00"),
                        new BigDecimal("90.00")
                ),
                List.of(),
                EmployeeRatingHistoryView.live()
        );
    }
}
