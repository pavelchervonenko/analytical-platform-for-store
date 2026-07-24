package com.storeanalytics.salary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeanalytics.salary.model.PayrollRun;
import com.storeanalytics.salary.repository.PayrollRunRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PayrollRunMetricsTest {

    @Test
    void exposesLatestIncompleteAndStaleRunCounts() {
        PayrollRunRepository repository = mock(PayrollRunRepository.class);
        PayrollFreshnessService freshnessService = mock(
                PayrollFreshnessService.class
        );
        PayrollRun incomplete = mock(PayrollRun.class);
        PayrollRun current = mock(PayrollRun.class);
        when(incomplete.isCalculationComplete()).thenReturn(false);
        when(current.isCalculationComplete()).thenReturn(true);
        when(repository.findLatestByStatusIn(any())).thenReturn(List.of(
                incomplete, current
        ));
        when(freshnessService.evaluate(incomplete)).thenReturn(freshness(
                PayrollFreshnessStatus.STALE
        ));
        when(freshnessService.evaluate(current)).thenReturn(freshness(
                PayrollFreshnessStatus.CURRENT
        ));
        PayrollRunMetrics metrics = new PayrollRunMetrics(
                repository, freshnessService
        );
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        metrics.bindTo(registry);

        metrics.refresh();

        assertThat(registry.get(PayrollRunMetrics.RUNS_METRIC)
                .tag("state", "incomplete")
                .gauge()
                .value()).isEqualTo(1);
        assertThat(registry.get(PayrollRunMetrics.RUNS_METRIC)
                .tag("state", "stale")
                .gauge()
                .value()).isEqualTo(1);
        assertThat(registry.get(PayrollRunMetrics.RUNS_METRIC)
                .tag("state", "unknown")
                .gauge()
                .value()).isZero();
    }

    private PayrollFreshnessView freshness(PayrollFreshnessStatus status) {
        return new PayrollFreshnessView(
                status,
                status != PayrollFreshnessStatus.CURRENT,
                List.of(),
                Instant.parse("2026-07-24T12:00:00Z")
        );
    }
}
