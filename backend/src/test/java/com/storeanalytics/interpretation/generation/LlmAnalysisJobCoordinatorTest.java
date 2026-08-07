package com.storeanalytics.interpretation.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class LlmAnalysisJobCoordinatorTest {

    private static final Instant NOW = Instant.parse("2026-08-03T05:00:00Z");

    @Test
    void expiresAndRecoversBeforeClaimAndEmitsSignals() {
        LlmAnalysisJobClaimStore claimStore = mock(LlmAnalysisJobClaimStore.class);
        LlmAnalysisJobLifecycleStore lifecycleStore = mock(
                LlmAnalysisJobLifecycleStore.class
        );
        LlmAnalysisOperatorSignals signals = mock(LlmAnalysisOperatorSignals.class);
        LlmAnalysisJob expired = mock(LlmAnalysisJob.class);
        LlmAnalysisJob recovered = mock(LlmAnalysisJob.class);
        LlmAnalysisJob claimed = mock(LlmAnalysisJob.class);
        when(recovered.terminalReasonCode()).thenReturn(
                LlmAnalysisJobLifecycleStore.DEADLINE_EXCEEDED
        );
        when(lifecycleStore.expireOnePastDeadline(NOW))
                .thenReturn(Optional.of(expired));
        when(lifecycleStore.recoverOneExpiredLease(NOW.plusSeconds(30), NOW))
                .thenReturn(Optional.of(recovered));
        when(claimStore.claimNext("worker", Duration.ofMinutes(2), NOW))
                .thenReturn(Optional.of(claimed));
        LlmAnalysisJobCoordinator coordinator = new LlmAnalysisJobCoordinator(
                claimStore,
                lifecycleStore,
                signals,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        assertThat(coordinator.claimNext(
                "worker", Duration.ofMinutes(2), Duration.ofSeconds(30)
        )).contains(claimed);

        InOrder order = inOrder(lifecycleStore, claimStore);
        order.verify(lifecycleStore).expireOnePastDeadline(NOW);
        order.verify(lifecycleStore).recoverOneExpiredLease(NOW.plusSeconds(30), NOW);
        order.verify(claimStore).claimNext("worker", Duration.ofMinutes(2), NOW);
        verify(signals).deadlineExceeded(expired);
        verify(signals).recoveredLease(recovered);
        verify(signals).deadlineExceeded(recovered);
    }
}
