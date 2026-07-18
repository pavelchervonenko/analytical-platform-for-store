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

import com.storeanalytics.common.web.ApiExceptionHandler;
import com.storeanalytics.sync.exception.ReturnSyncCapacityException;
import com.storeanalytics.sync.exception.ReturnSyncException;
import com.storeanalytics.sync.model.SyncStatus;
import com.storeanalytics.sync.service.ReturnSyncPeriod;
import com.storeanalytics.sync.service.ReturnSyncResult;
import com.storeanalytics.sync.service.ReturnSyncService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ReturnSyncControllerTest {

    private ReturnSyncService returnSyncService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        returnSyncService = mock(ReturnSyncService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ReturnSyncController(returnSyncService))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void startsReturnSyncForExplicitPeriod() throws Exception {
        UUID syncRunId = UUID.randomUUID();
        when(returnSyncService.synchronize(any(ReturnSyncPeriod.class)))
                .thenReturn(successfulResult(syncRunId));

        mockMvc.perform(validRequest())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.syncRunId").value(syncRunId.toString()))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.recordsFetched").value(1));

        ArgumentCaptor<ReturnSyncPeriod> period =
                ArgumentCaptor.forClass(ReturnSyncPeriod.class);
        verify(returnSyncService).synchronize(period.capture());
        assertThat(period.getValue().start())
                .isEqualTo(Instant.parse("2026-07-01T00:00:00Z"));
        assertThat(period.getValue().end())
                .isEqualTo(Instant.parse("2026-07-02T00:00:00Z"));
    }

    @Test
    void reportsCapacityLimitAsUnprocessableEntity() throws Exception {
        UUID syncRunId = UUID.randomUUID();
        when(returnSyncService.synchronize(any(ReturnSyncPeriod.class)))
                .thenThrow(new ReturnSyncCapacityException(syncRunId, 71, 70));

        mockMvc.perform(validRequest())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(
                        "RETURN_SYNC_WINDOW_TOO_LARGE"
                ))
                .andExpect(jsonPath("$.message").value(
                        "Return window contains 71 active documents; maximum is 70; "
                                + "sync run: " + syncRunId
                ));
    }

    @Test
    void reportsUpstreamFailureWithoutCauseDetails() throws Exception {
        UUID syncRunId = UUID.randomUUID();
        when(returnSyncService.synchronize(any(ReturnSyncPeriod.class)))
                .thenThrow(new ReturnSyncException(
                        syncRunId,
                        new IllegalStateException("sensitive source detail")
                ));

        mockMvc.perform(validRequest())
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("RETURN_SYNC_FAILED"))
                .andExpect(jsonPath("$.message").value(
                        "Return synchronization failed; sync run: " + syncRunId
                ));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            validRequest() {
        return post("/api/sync/returns")
                .contentType(APPLICATION_JSON)
                .content("""
                        {
                          "periodStart": "2026-07-01T00:00:00Z",
                          "periodEnd": "2026-07-02T00:00:00Z"
                        }
                        """);
    }

    private ReturnSyncResult successfulResult(UUID syncRunId) {
        return new ReturnSyncResult(
                syncRunId,
                SyncStatus.SUCCESS,
                1,
                1,
                0,
                0,
                0,
                1,
                0,
                0,
                0,
                0,
                0,
                1,
                0,
                0,
                1,
                0,
                0,
                0,
                0,
                0
        );
    }
}
