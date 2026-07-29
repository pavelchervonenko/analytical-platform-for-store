package com.storeanalytics.integration.livesklad.health;

import com.storeanalytics.common.config.ApplicationRole;
import com.storeanalytics.common.config.BackgroundSchedulingConfiguration;
import com.storeanalytics.common.config.ConditionalOnApplicationRole;
import com.storeanalytics.common.config.LiveSkladProperties;
import com.storeanalytics.integration.livesklad.client.LiveSkladClient;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConditionalOnApplicationRole({ApplicationRole.WORKER, ApplicationRole.COMBINED})
public class LiveSkladAvailabilityProbe {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            LiveSkladAvailabilityProbe.class
    );

    private final LiveSkladClient liveSkladClient;
    private final LiveSkladProperties properties;
    private final Clock clock;
    private final AtomicReference<LiveSkladAvailabilityState> state;

    public LiveSkladAvailabilityProbe(
            LiveSkladClient liveSkladClient,
            LiveSkladProperties properties,
            Clock clock
    ) {
        this.liveSkladClient = liveSkladClient;
        this.properties = properties;
        this.clock = clock;
        state = new AtomicReference<>(new LiveSkladAvailabilityState(
                configured()
                        ? LiveSkladAvailability.UNKNOWN
                        : LiveSkladAvailability.NOT_CONFIGURED,
                null
        ));
    }

    @Scheduled(
            initialDelayString = "${app.observability.livesklad-initial-delay:30s}",
            fixedDelayString = "${app.observability.livesklad-refresh-delay:1m}",
            scheduler = BackgroundSchedulingConfiguration
                    .LIVESKLAD_PROBE_SCHEDULER
    )
    public void probe() {
        if (!configured()) {
            state.set(new LiveSkladAvailabilityState(
                    LiveSkladAvailability.NOT_CONFIGURED,
                    clock.instant()
            ));
            return;
        }
        try {
            liveSkladClient.fetchStores();
            state.set(new LiveSkladAvailabilityState(
                    LiveSkladAvailability.UP,
                    clock.instant()
            ));
        } catch (RuntimeException exception) {
            state.set(new LiveSkladAvailabilityState(
                    LiveSkladAvailability.DOWN,
                    clock.instant()
            ));
            LOGGER.error("LiveSklad availability probe failed", exception);
        }
    }

    public LiveSkladAvailabilityState current() {
        return state.get();
    }

    private boolean configured() {
        return StringUtils.hasText(properties.baseUrl())
                && StringUtils.hasText(properties.login())
                && StringUtils.hasText(properties.password());
    }
}
