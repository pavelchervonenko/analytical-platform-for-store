package com.storeanalytics.quality.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeanalytics.quality.model.DataQualityStatus;
import com.storeanalytics.quality.repository.DataQualityIssueRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class DataQualityMetricsTest {

    @Test
    void exposesCachedOpenIssueCount() {
        DataQualityIssueRepository repository = mock(
                DataQualityIssueRepository.class
        );
        when(repository.countByStatus(DataQualityStatus.OPEN)).thenReturn(7L);
        DataQualityMetrics metrics = new DataQualityMetrics(repository);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        metrics.bindTo(registry);

        metrics.refresh();

        assertThat(registry.get(DataQualityMetrics.ISSUES_METRIC)
                .tag("status", "open")
                .gauge()
                .value()).isEqualTo(7);
    }
}
