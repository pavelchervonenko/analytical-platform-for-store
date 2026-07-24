package com.storeanalytics.integration.livesklad.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeanalytics.common.config.LiveSkladProperties;
import com.storeanalytics.integration.livesklad.client.LiveSkladClient;
import com.storeanalytics.integration.livesklad.exception.LiveSkladException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

class LiveSkladHealthIndicatorTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-24T12:00:00Z"),
            ZoneOffset.UTC
    );

    @Test
    void reportsCachedUpStateAfterSuccessfulProbe() {
        LiveSkladClient client = mock(LiveSkladClient.class);
        when(client.fetchStores()).thenReturn(List.of());
        LiveSkladAvailabilityProbe probe = new LiveSkladAvailabilityProbe(
                client, configuredProperties(), CLOCK
        );

        probe.probe();

        assertThat(new LiveSkladHealthIndicator(probe)
                .health()
                .getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void reportsDownWithoutExposingUpstreamMessage() {
        LiveSkladClient client = mock(LiveSkladClient.class);
        when(client.fetchStores()).thenThrow(new LiveSkladException(
                "secret upstream body"
        ));
        LiveSkladAvailabilityProbe probe = new LiveSkladAvailabilityProbe(
                client, configuredProperties(), CLOCK
        );

        probe.probe();
        org.springframework.boot.actuate.health.Health health =
                new LiveSkladHealthIndicator(probe).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().toString())
                .doesNotContain("secret upstream body");
    }

    @Test
    void reportsUnknownWithoutNetworkCallWhenNotConfigured() {
        LiveSkladClient client = mock(LiveSkladClient.class);
        LiveSkladAvailabilityProbe probe = new LiveSkladAvailabilityProbe(
                client,
                new LiveSkladProperties(
                        "", "", "", Duration.ofSeconds(1), Duration.ofSeconds(1)
                ),
                CLOCK
        );

        probe.probe();

        assertThat(new LiveSkladHealthIndicator(probe)
                .health()
                .getStatus()).isEqualTo(Status.UNKNOWN);
        verifyNoInteractions(client);
    }

    private LiveSkladProperties configuredProperties() {
        return new LiveSkladProperties(
                "https://livesklad.example",
                "login",
                "password",
                Duration.ofSeconds(1),
                Duration.ofSeconds(1)
        );
    }
}
