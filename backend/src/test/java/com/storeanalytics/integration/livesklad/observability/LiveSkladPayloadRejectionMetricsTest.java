package com.storeanalytics.integration.livesklad.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.integration.livesklad.exception.LiveSkladPayloadRejectedException.Reason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class LiveSkladPayloadRejectionMetricsTest {

    @Test
    void exposesOneBoundedReasonSeriesForEveryRejectionKind() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LiveSkladPayloadRejectionMetrics metrics =
                new LiveSkladPayloadRejectionMetrics(registry);

        Arrays.stream(Reason.values()).forEach(metrics::record);

        for (Reason reason : Reason.values()) {
            assertThat(registry.get(
                    LiveSkladPayloadRejectionMetrics.REJECTIONS_METRIC
            ).tag(
                    "reason",
                    reason.name().toLowerCase(Locale.ROOT)
            ).counter().count()).isEqualTo(1.0);
        }
        assertThat(registry.find(
                LiveSkladPayloadRejectionMetrics.REJECTIONS_METRIC
        ).meters()).hasSize(Reason.values().length);
    }
}
