package com.storeanalytics.notification.daily;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class DailyStorePulsePropertiesTest {

    @Test
    void rejectsAnEmptyOrReversedDeliveryWindow() {
        assertThatThrownBy(() -> new DailyStorePulseProperties(
                true,
                Duration.ofMinutes(5),
                LocalTime.of(14, 0),
                LocalTime.of(8, 0),
                "policy-v1",
                "render-v1"
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
