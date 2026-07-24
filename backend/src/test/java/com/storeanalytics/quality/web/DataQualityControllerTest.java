package com.storeanalytics.quality.web;
import com.storeanalytics.common.web.ApiExceptionHandler;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.storeanalytics.auth.model.UserRole;
import com.storeanalytics.auth.security.AppUserPrincipal;
import com.storeanalytics.metrics.exception.StoreNotFoundException;
import com.storeanalytics.quality.model.DataQualitySeverity;
import com.storeanalytics.quality.service.DataQualityHealthStatus;
import com.storeanalytics.quality.service.DataQualityIssueView;
import com.storeanalytics.quality.service.DataQualityOverviewView;
import com.storeanalytics.quality.service.DataQualityRecommendedAction;
import com.storeanalytics.quality.service.DataQualityService;
import com.storeanalytics.quality.service.DataQualitySource;
import com.storeanalytics.quality.service.StoreDataQualitySummaryView;
import com.storeanalytics.quality.service.StoreDataQualityView;
import com.storeanalytics.store.service.StoreDataFreshnessStatus;
import com.storeanalytics.store.service.StoreDataStatusView;
import com.storeanalytics.store.service.StoreSyncActivityView;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DataQualityControllerTest {

    private static final Instant CHECKED_AT = Instant.parse("2026-07-22T10:00:00Z");

    private DataQualityService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(DataQualityService.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mockMvc = MockMvcBuilders.standaloneSetup(new DataQualityController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void returnsStableOverviewContractForCurrentPrincipal() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        AppUserPrincipal principal = mock(AppUserPrincipal.class);
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(principal);
        when(principal.getUserId()).thenReturn(userId);
        when(principal.getRole()).thenReturn(UserRole.MANAGER);
        when(service.overview(userId, UserRole.MANAGER)).thenReturn(new DataQualityOverviewView(
                CHECKED_AT,
                1,
                0,
                1,
                0,
                1,
                List.of(summary(storeId))
        ));

        mockMvc.perform(get("/api/data-quality/summary").principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkedAt").value("2026-07-22T10:00:00Z"))
                .andExpect(jsonPath("$.storeCount").value(1))
                .andExpect(jsonPath("$.warningStoreCount").value(1))
                .andExpect(jsonPath("$.openIssueCount").value(1))
                .andExpect(jsonPath("$.stores[0].storeId").value(storeId.toString()))
                .andExpect(jsonPath("$.stores[0].status").value("WARNING"));
    }

    @Test
    void detailContractExcludesRawEntityIdentifiersAndMetadata() throws Exception {
        UUID storeId = UUID.randomUUID();
        StoreDataStatusView dataStatus = dataStatus(storeId);
        DataQualityIssueView issue = new DataQualityIssueView(
                "QUALITY_ISSUE:" + UUID.randomUUID(),
                DataQualitySource.SALES,
                "SALE_PAYMENT_MISMATCH",
                DataQualitySeverity.WARNING,
                "SALE",
                "Sale payments do not match the document total",
                Instant.parse("2026-07-22T09:00:00Z"),
                DataQualityRecommendedAction.REVIEW_SOURCE_DOCUMENT
        );
        when(service.get(storeId)).thenReturn(new StoreDataQualityView(
                summary(storeId), dataStatus, List.of(issue)
        ));

        mockMvc.perform(get("/api/stores/{storeId}/data-quality", storeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.storeId").value(storeId.toString()))
                .andExpect(jsonPath("$.dataStatus.status").value("STALE"))
                .andExpect(jsonPath("$.issues[0].source").value("SALES"))
                .andExpect(jsonPath("$.issues[0].code").value("SALE_PAYMENT_MISMATCH"))
                .andExpect(jsonPath("$.issues[0].recommendedAction")
                        .value("REVIEW_SOURCE_DOCUMENT"))
                .andExpect(jsonPath("$.issues[0].entityId").doesNotExist())
                .andExpect(jsonPath("$.issues[0].metadata").doesNotExist());
    }

    @Test
    void returnsCanonicalNotFoundError() throws Exception {
        UUID storeId = UUID.randomUUID();
        when(service.get(storeId)).thenThrow(new StoreNotFoundException(storeId));

        mockMvc.perform(get("/api/stores/{storeId}/data-quality", storeId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("STORE_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value(
                        "/api/stores/" + storeId + "/data-quality"
                ));
    }

    private StoreDataQualitySummaryView summary(UUID storeId) {
        return new StoreDataQualitySummaryView(
                storeId,
                "Central",
                DataQualityHealthStatus.WARNING,
                StoreDataFreshnessStatus.STALE,
                LocalDate.of(2026, 7, 20),
                1,
                1,
                0,
                1,
                0,
                CHECKED_AT
        );
    }

    private StoreDataStatusView dataStatus(UUID storeId) {
        return new StoreDataStatusView(
                storeId,
                StoreDataFreshnessStatus.STALE,
                LocalDate.of(2026, 7, 21),
                LocalDate.of(2026, 7, 20),
                LocalDate.of(2026, 7, 20),
                LocalDate.of(2026, 7, 20),
                1,
                Instant.parse("2026-07-22T07:00:00Z"),
                new StoreSyncActivityView(false, null, null, null, null, null, null),
                0,
                null,
                null,
                CHECKED_AT
        );
    }
}
