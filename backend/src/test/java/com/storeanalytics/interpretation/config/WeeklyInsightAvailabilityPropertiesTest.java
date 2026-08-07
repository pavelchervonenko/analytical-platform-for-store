package com.storeanalytics.interpretation.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class WeeklyInsightAvailabilityPropertiesTest {

    @Test
    void rejectsUnsafeSlaAndRefreshIntervals() {
        assertThatThrownBy(() -> new WeeklyInsightAvailabilityProperties(
                Duration.ZERO,
                Duration.ofSeconds(15),
                LocalTime.of(8, 0)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WeeklyInsightAvailabilityProperties(
                Duration.ofMinutes(5),
                Duration.ofMinutes(6),
                LocalTime.of(8, 0)
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
