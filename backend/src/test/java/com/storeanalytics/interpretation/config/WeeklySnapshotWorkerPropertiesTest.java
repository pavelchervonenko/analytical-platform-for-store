package com.storeanalytics.interpretation.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class WeeklySnapshotWorkerPropertiesTest {

    @Test
    void acceptsLeaseThatCoversAtLeastTwoHeartbeats() {
        WeeklySnapshotWorkerProperties properties = properties(
                Duration.ofMinutes(10),
                Duration.ofMinutes(1),
                Duration.ofSeconds(30),
                Duration.ofMinutes(15)
        );

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.leaseDuration()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void rejectsNonPositiveWorkerDelay() {
        assertThatThrownBy(() -> new WeeklySnapshotWorkerProperties(
                true,
                Duration.ZERO,
                Duration.ofMinutes(10),
                Duration.ofMinutes(1),
                Duration.ofSeconds(30),
                Duration.ofMinutes(15)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workerDelay");
    }

    @Test
    void rejectsUnsafeLeaseAndRetryRelationships() {
        assertThatThrownBy(() -> properties(
                Duration.ofMinutes(1),
                Duration.ofMinutes(1),
                Duration.ofSeconds(30),
                Duration.ofMinutes(15)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("heartbeatInterval");
        assertThatThrownBy(() -> properties(
                Duration.ofMinutes(10),
                Duration.ofMinutes(1),
                Duration.ofMinutes(2),
                Duration.ofMinutes(1)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retryMaxDelay");
    }

    private WeeklySnapshotWorkerProperties properties(
            Duration lease,
            Duration heartbeat,
            Duration retryInitial,
            Duration retryMax
    ) {
        return new WeeklySnapshotWorkerProperties(
                true,
                Duration.ofSeconds(5),
                lease,
                heartbeat,
                retryInitial,
                retryMax
        );
    }
}
