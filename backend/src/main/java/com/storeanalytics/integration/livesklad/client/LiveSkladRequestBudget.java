package com.storeanalytics.integration.livesklad.client;

import com.storeanalytics.integration.livesklad.exception.LiveSkladRateLimitException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

final class LiveSkladRequestBudget {

    private static final int REQUEST_RESERVE = 5;

    private final Clock clock;
    private Integer remaining;
    private Instant resetsAt;

    LiveSkladRequestBudget(Clock clock) {
        this.clock = clock;
    }

    synchronized void beforeRequest() {
        Instant now = clock.instant();
        if (resetsAt != null && !now.isBefore(resetsAt)) {
            remaining = null;
            resetsAt = null;
            return;
        }
        if (remaining != null && remaining <= REQUEST_RESERVE && resetsAt != null) {
            Duration retryAfter = Duration.between(now, resetsAt).plusSeconds(1);
            throw new LiveSkladRateLimitException(
                    "LiveSklad request reserve reached",
                    retryAfter
            );
        }
    }

    synchronized void observe(Integer remainingRequests, Instant expiration) {
        if (remainingRequests == null || expiration == null) {
            return;
        }
        remaining = Math.max(0, remainingRequests);
        resetsAt = expiration;
    }
}
