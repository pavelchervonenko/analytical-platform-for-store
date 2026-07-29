package com.storeanalytics.common.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class SyncPropertiesTest {

    @Test
    void rejectsAbsoluteRetryCapBelowExponentialBackoffCap() {
        assertThatThrownBy(() -> properties(
                Duration.ofMinutes(15),
                Duration.ofMinutes(10)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retryAbsoluteMaxDelay");
    }

    @Test
    void rejectsUnboundedAbsoluteRetryCap() {
        assertThatThrownBy(() -> properties(
                Duration.ofMinutes(15),
                Duration.ofDays(8)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retryAbsoluteMaxDelay");
    }

    private SyncProperties properties(
            Duration retryMaxDelay,
            Duration retryAbsoluteMaxDelay
    ) {
        return new SyncProperties(
                Duration.ofDays(1),
                5,
                Duration.ofHours(2),
                Duration.ofMinutes(1),
                retryMaxDelay,
                retryAbsoluteMaxDelay,
                3,
                730,
                ZoneId.of("Europe/Kaliningrad")
        );
    }
}
