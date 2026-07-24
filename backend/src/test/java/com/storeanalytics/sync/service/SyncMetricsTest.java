package com.storeanalytics.sync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.sync.model.SyncScope;
import com.storeanalytics.sync.model.SyncTriggerType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class SyncMetricsTest {

    @Test
    void recordsSuccessfulAndFailedOperationsWithoutExceptionTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SyncMetrics metrics = new SyncMetrics(registry);

        assertThat(metrics.record(
                SyncScope.SALES,
                SyncTriggerType.MANUAL,
                () -> "done"
        )).isEqualTo("done");
        assertThatThrownBy(() -> metrics.record(
                SyncScope.SALES,
                SyncTriggerType.MANUAL,
                () -> {
                    throw new IllegalStateException("internal");
                }
        )).isInstanceOf(IllegalStateException.class);

        assertThat(registry.get(SyncMetrics.DURATION_METRIC)
                .tag("scope", "sales")
                .tag("trigger", "manual")
                .tag("outcome", "success")
                .timer()
                .count()).isEqualTo(1);
        assertThat(registry.get(SyncMetrics.DURATION_METRIC)
                .tag("scope", "sales")
                .tag("trigger", "manual")
                .tag("outcome", "failure")
                .timer()
                .count()).isEqualTo(1);
    }
}
