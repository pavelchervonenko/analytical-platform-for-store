package com.storeanalytics.integration.livesklad.health;

import java.time.Instant;

public record LiveSkladAvailabilityState(
        LiveSkladAvailability availability,
        Instant checkedAt
) {
}
