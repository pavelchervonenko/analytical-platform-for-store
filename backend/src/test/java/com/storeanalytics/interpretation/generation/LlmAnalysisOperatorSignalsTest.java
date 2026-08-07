package com.storeanalytics.interpretation.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class LlmAnalysisOperatorSignalsTest {

    @Test
    void countsLeaseRecoveryAndDeadlineEvents() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LlmAnalysisOperatorSignals signals = new LlmAnalysisOperatorSignals(registry);
        LlmAnalysisJob job = mock(LlmAnalysisJob.class);
        when(job.status()).thenReturn(LlmAnalysisJobStatus.FAILED);
        when(job.phase()).thenReturn(LlmAnalysisPhase.CALL_PROVIDER);

        signals.recoveredLease(job);
        signals.deadlineExceeded(job);

        assertThat(registry.get(LlmAnalysisOperatorSignals.EVENTS_METRIC)
                .tag("event", "expired_lease_recovered")
                .counter()
                .count()).isOne();
        assertThat(registry.get(LlmAnalysisOperatorSignals.EVENTS_METRIC)
                .tag("event", "deadline_exceeded")
                .counter()
                .count()).isOne();
    }
}
