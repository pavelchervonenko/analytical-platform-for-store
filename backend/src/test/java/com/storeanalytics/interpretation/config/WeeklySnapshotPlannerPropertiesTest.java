package com.storeanalytics.interpretation.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class WeeklySnapshotPlannerPropertiesTest {

    @Test
    void acceptsProductionDefaultsShape() {
        WeeklySnapshotPlannerProperties properties = properties(
                Duration.ofMinutes(1),
                Duration.ofHours(72),
                25,
                5
        );

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.revisionWindow()).isEqualTo(Duration.ofHours(72));
    }

    @Test
    void rejectsUnsafeDurationsAndBounds() {
        assertThatThrownBy(() -> properties(
                Duration.ZERO, Duration.ofHours(72), 25, 5
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scanDelay");
        assertThatThrownBy(() -> properties(
                Duration.ofMinutes(1), Duration.ofDays(8), 25, 5
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("revisionWindow");
        assertThatThrownBy(() -> properties(
                Duration.ofMinutes(1), Duration.ofHours(72), 0, 5
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batchSize");
        assertThatThrownBy(() -> properties(
                Duration.ofMinutes(1), Duration.ofHours(72), 25, 21
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxAttempts");
    }

    private WeeklySnapshotPlannerProperties properties(
            Duration scanDelay,
            Duration revisionWindow,
            int batchSize,
            int maxAttempts
    ) {
        return new WeeklySnapshotPlannerProperties(
                true,
                scanDelay,
                revisionWindow,
                batchSize,
                maxAttempts
        );
    }
}
