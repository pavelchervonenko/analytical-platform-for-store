package com.storeanalytics.store.web;
import com.storeanalytics.common.web.ApiExceptionHandler;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.storeanalytics.metrics.exception.StoreNotFoundException;
import com.storeanalytics.store.service.StoreDataFreshnessStatus;
import com.storeanalytics.store.service.StoreDataStatusService;
import com.storeanalytics.store.service.StoreDataStatusView;
import com.storeanalytics.store.service.StoreSyncActivityType;
import com.storeanalytics.store.service.StoreSyncActivityView;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class StoreDataStatusControllerTest {

    private StoreDataStatusService statusService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        statusService = mock(StoreDataStatusService.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        StoreDataStatusController controller = new StoreDataStatusController(statusService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void returnsStableDataFreshnessContract() throws Exception {
        UUID storeId = UUID.randomUUID();
        UUID syncId = UUID.randomUUID();
        Instant checkedAt = Instant.parse("2026-07-22T08:00:00Z");
        when(statusService.get(storeId)).thenReturn(new StoreDataStatusView(
                storeId,
                StoreDataFreshnessStatus.SYNCING,
                LocalDate.of(2026, 7, 21),
                LocalDate.of(2026, 7, 20),
                LocalDate.of(2026, 7, 21),
                LocalDate.of(2026, 7, 20),
                1,
                Instant.parse("2026-07-22T06:30:00Z"),
                new StoreSyncActivityView(
                        true,
                        syncId,
                        StoreSyncActivityType.JOB,
                        "WAITING_RETRY",
                        "RETURNS",
                        Instant.parse("2026-07-22T06:00:00Z"),
                        Instant.parse("2026-07-22T08:05:00Z")
                ),
                3,
                "Return synchronization failed: TimeoutException",
                Instant.parse("2026-07-22T07:55:00Z"),
                checkedAt
        ));

        mockMvc.perform(get("/api/stores/{storeId}/data-status", storeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value(storeId.toString()))
                .andExpect(jsonPath("$.status").value("SYNCING"))
                .andExpect(jsonPath("$.expectedThroughDate").value("2026-07-21"))
                .andExpect(jsonPath("$.dataThroughDate").value("2026-07-20"))
                .andExpect(jsonPath("$.salesDataThroughDate").value("2026-07-21"))
                .andExpect(jsonPath("$.returnsDataThroughDate").value("2026-07-20"))
                .andExpect(jsonPath("$.lagDays").value(1))
                .andExpect(jsonPath("$.lastCompletedSyncAt").value("2026-07-22T06:30:00Z"))
                .andExpect(jsonPath("$.synchronization.active").value(true))
                .andExpect(jsonPath("$.synchronization.id").value(syncId.toString()))
                .andExpect(jsonPath("$.synchronization.type").value("JOB"))
                .andExpect(jsonPath("$.synchronization.status").value("WAITING_RETRY"))
                .andExpect(jsonPath("$.synchronization.phase").value("RETURNS"))
                .andExpect(jsonPath("$.openQualityIssueCount").value(3))
                .andExpect(jsonPath("$.lastError").value(
                        "Return synchronization failed: TimeoutException"
                ))
                .andExpect(jsonPath("$.checkedAt").value("2026-07-22T08:00:00Z"));
    }

    @Test
    void returnsNotFoundForUnknownStore() throws Exception {
        UUID storeId = UUID.randomUUID();
        when(statusService.get(storeId)).thenThrow(new StoreNotFoundException(storeId));

        mockMvc.perform(get("/api/stores/{storeId}/data-status", storeId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STORE_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value(
                        "/api/stores/" + storeId + "/data-status"
                ));
    }
}
