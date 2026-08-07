package com.storeanalytics.interpretation.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class LlmAnalysisPlannerPropertiesTest {

    @Test
    void rejectsUnsafeSchedulingAndDeadlineValues() {
        assertThatThrownBy(() -> properties(Duration.ZERO, 25, Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(Duration.ofMinutes(1), 0, Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(Duration.ofMinutes(1), 25, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(Duration.ofMinutes(1), 25, Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private LlmAnalysisPlannerProperties properties(
            Duration scanDelay,
            int batchSize,
            Duration deadline
    ) {
        return new LlmAnalysisPlannerProperties(
                true,
                scanDelay,
                batchSize,
                deadline
        );
    }
}
