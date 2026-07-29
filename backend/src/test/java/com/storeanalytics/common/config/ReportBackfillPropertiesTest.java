package com.storeanalytics.common.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ReportBackfillPropertiesTest {

    @Test
    void acceptsProductionDefaults() {
        assertThatCode(() -> properties(3, 20)).doesNotThrowAnyException();
    }

    @Test
    void rejectsUnsafeAttemptsAndCapacity() {
        assertThatThrownBy(() -> properties(0, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(3, 101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ReportBackfillProperties properties(int attempts, int capacity) {
        return new ReportBackfillProperties(
                attempts,
                Duration.ofMinutes(30),
                Duration.ofSeconds(30),
                Duration.ofMinutes(15),
                capacity
        );
    }
}
