package com.storeanalytics.sync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeanalytics.sync.model.SyncJobStatus;
import com.storeanalytics.sync.repository.SyncJobRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class SyncJobStateMetricsTest {

    @Test
    void exposesCachedFailedAndRetryingCounts() {
        SyncJobRepository repository = mock(SyncJobRepository.class);
        when(repository.countByStatus(SyncJobStatus.FAILED)).thenReturn(3L);
        when(repository.countByStatus(SyncJobStatus.WAITING_RETRY)).thenReturn(2L);
        SyncJobStateMetrics metrics = new SyncJobStateMetrics(repository);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        metrics.bindTo(registry);

        metrics.refresh();

        assertThat(registry.get(SyncJobStateMetrics.JOBS_METRIC)
                .tag("status", "failed")
                .gauge()
                .value()).isEqualTo(3);
        assertThat(registry.get(SyncJobStateMetrics.JOBS_METRIC)
                .tag("status", "retrying")
                .gauge()
                .value()).isEqualTo(2);
    }
}
