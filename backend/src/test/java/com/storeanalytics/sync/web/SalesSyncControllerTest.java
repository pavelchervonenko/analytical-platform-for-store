package com.storeanalytics.sync.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storeanalytics.auth.security.AppUserPrincipal;
import com.storeanalytics.common.web.ApiExceptionHandler;
import com.storeanalytics.sync.exception.SalesSyncCapacityException;
import com.storeanalytics.sync.exception.SalesSyncException;
import com.storeanalytics.sync.model.SyncStatus;
import com.storeanalytics.sync.service.ManualSyncAuditService;
import com.storeanalytics.sync.service.SalesSyncPeriod;
import com.storeanalytics.sync.service.SalesSyncResult;
import com.storeanalytics.sync.service.SalesSyncService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SalesSyncControllerTest {

    private SalesSyncService salesSyncService;
    private MockMvc mockMvc;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        salesSyncService = mock(SalesSyncService.class);
        AppUserPrincipal principal = mock(AppUserPrincipal.class);
        when(principal.getUserId()).thenReturn(UUID.randomUUID());
        authentication = new TestingAuthenticationToken(principal, null);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new SalesSyncController(
                        salesSyncService,
                        mock(ManualSyncAuditService.class)
                ))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void startsSalesSyncForExplicitPeriod() throws Exception {
        UUID syncRunId = UUID.randomUUID();
        when(salesSyncService.synchronize(any(SalesSyncPeriod.class)))
                .thenReturn(successfulResult(syncRunId));

        mockMvc.perform(post("/api/sync/sales")
                        .principal(authentication)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "periodStart": "2026-07-01T00:00:00Z",
                                  "periodEnd": "2026-07-02T00:00:00Z"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.syncRunId").value(syncRunId.toString()))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.recordsFetched").value(1));

        ArgumentCaptor<SalesSyncPeriod> period =
                ArgumentCaptor.forClass(SalesSyncPeriod.class);
        verify(salesSyncService).synchronize(period.capture());
        assertThat(period.getValue().start())
                .isEqualTo(Instant.parse("2026-07-01T00:00:00Z"));
        assertThat(period.getValue().end())
                .isEqualTo(Instant.parse("2026-07-02T00:00:00Z"));
    }

    @Test
    void rejectsMissingPeriodBoundaryBeforeCallingService() throws Exception {
        mockMvc.perform(post("/api/sync/sales")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "periodStart": "2026-07-01T00:00:00Z"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.path").value("/api/sync/sales"));
    }

    @Test
    void rejectsInvalidPeriodWithoutLeakingValidationDetails() throws Exception {
        mockMvc.perform(post("/api/sync/sales")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "periodStart": "2026-07-02T00:00:00Z",
                                  "periodEnd": "2026-07-01T00:00:00Z"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value(
                        "Request parameters are invalid"
                ));
    }

    @Test
    void reportsCapacityLimitAsUnprocessableEntity() throws Exception {
        UUID syncRunId = UUID.randomUUID();
        when(salesSyncService.synchronize(any(SalesSyncPeriod.class)))
                .thenThrow(new SalesSyncCapacityException(syncRunId, 71, 70));

        mockMvc.perform(validRequest())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(
                        "SALES_SYNC_WINDOW_TOO_LARGE"
                ))
                .andExpect(jsonPath("$.message").value(
                        "Sales synchronization window is too large"
                ));
    }

    @Test
    void reportsUpstreamFailureWithoutCauseDetails() throws Exception {
        UUID syncRunId = UUID.randomUUID();
        when(salesSyncService.synchronize(any(SalesSyncPeriod.class)))
                .thenThrow(new SalesSyncException(
                        syncRunId,
                        new IllegalStateException("sensitive source detail")
                ));

        mockMvc.perform(validRequest())
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("SALES_SYNC_FAILED"))
                .andExpect(jsonPath("$.message").value(
                        "Sales synchronization failed"
                ));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            validRequest() {
        return post("/api/sync/sales")
                .principal(authentication)
                .contentType(APPLICATION_JSON)
                .content("""
                        {
                          "periodStart": "2026-07-01T00:00:00Z",
                          "periodEnd": "2026-07-02T00:00:00Z"
                        }
                        """);
    }

    private SalesSyncResult successfulResult(UUID syncRunId) {
        return new SalesSyncResult(
                syncRunId,
                SyncStatus.SUCCESS,
                1,
                1,
                0,
                0,
                0,
                0,
                1,
                0,
                1,
                0,
                0,
                1,
                0,
                0,
                1,
                0
        );
    }
}
