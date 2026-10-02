package com.storeanalytics.sync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.sync.model.SyncTriggerType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SyncExecutionContextTest {

    @Test
    void acceptsZeroFailureCounterForFirstDurableAttempt() {
        UUID jobId = UUID.randomUUID();

        SyncExecutionContext context = new SyncExecutionContext(
                SyncTriggerType.SCHEDULED, jobId, null, 0);

        assertThat(context.jobAttempt()).isZero();
        assertThat(context.syncJobId()).isEqualTo(jobId);
    }

    @Test
    void preservesPersistedCounterForRetryFence() {
        SyncExecutionContext context = new SyncExecutionContext(
                SyncTriggerType.INITIAL, UUID.randomUUID(), null, 2);

        assertThat(context.jobAttempt()).isEqualTo(2);
    }

    @Test
    void rejectsNegativeFailureCounter() {
        assertThatThrownBy(() -> new SyncExecutionContext(
                SyncTriggerType.SCHEDULED, UUID.randomUUID(), null, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preservesManualAndDurableIdentityGuards() {
        assertThatCode(SyncExecutionContext::manual).doesNotThrowAnyException();
        assertThatThrownBy(() -> new SyncExecutionContext(
                SyncTriggerType.MANUAL, UUID.randomUUID(), null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SyncExecutionContext(
                SyncTriggerType.SCHEDULED, null, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
