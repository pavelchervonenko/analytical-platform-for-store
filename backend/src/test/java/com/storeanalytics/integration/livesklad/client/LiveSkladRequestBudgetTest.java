package com.storeanalytics.integration.livesklad.client;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.integration.livesklad.exception.LiveSkladRateLimitException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class LiveSkladRequestBudgetTest {

    private final MutableClock clock = new MutableClock(
            Instant.parse("2026-07-20T12:00:00Z")
    );
    private final LiveSkladRequestBudget budget = new LiveSkladRequestBudget(clock);

    @Test
    void preservesFiveRequestReserveUntilSourceWindowExpires() {
        budget.observe(5, clock.instant().plusSeconds(60));

        assertThatThrownBy(budget::beforeRequest)
                .isInstanceOf(LiveSkladRateLimitException.class)
                .satisfies(exception -> {
                    LiveSkladRateLimitException rateLimit =
                            (LiveSkladRateLimitException) exception;
                    org.assertj.core.api.Assertions.assertThat(rateLimit.getRetryAfter())
                            .isEqualTo(java.time.Duration.ofSeconds(61));
                });

        clock.advanceSeconds(60);
        assertThatNoException().isThrownBy(budget::beforeRequest);
    }

    @Test
    void allowsRequestsWhileReserveIsNotReached() {
        budget.observe(6, clock.instant().plusSeconds(60));

        assertThatNoException().isThrownBy(budget::beforeRequest);
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
