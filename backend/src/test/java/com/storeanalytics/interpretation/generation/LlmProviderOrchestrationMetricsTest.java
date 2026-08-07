package com.storeanalytics.interpretation.generation;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class LlmProviderOrchestrationMetricsTest {

    @Test
    void countsPreflightRejectionsByBoundedReason() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LlmProviderOrchestrationMetrics metrics =
                new LlmProviderOrchestrationMetrics(registry);

        metrics.preflightRejected("COST_BUDGET_EXCEEDED");
        metrics.preflightRejected("COST_BUDGET_EXCEEDED");

        assertThat(registry.get(
                LlmProviderOrchestrationMetrics.PREFLIGHT_REJECTIONS
        )
                .tag("reason", "COST_BUDGET_EXCEEDED")
                .counter()
                .count()).isEqualTo(2.0);
    }
}
