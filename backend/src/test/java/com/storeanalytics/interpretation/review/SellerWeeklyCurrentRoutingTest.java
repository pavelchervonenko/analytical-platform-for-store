package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeanalytics.metrics.repository.SellerMembershipHistoryRepository;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.repository.StoreRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SellerWeeklyCurrentRoutingTest {
    private final UUID storeId = UUID.randomUUID();
    private final StoreRepository stores = mock(StoreRepository.class);
    private final SellerMembershipHistoryRepository history = mock(SellerMembershipHistoryRepository.class);
    private final Store store = mock(Store.class);

    @Test
    void disabledModeDoesNotInspectOrInferHistory() {
        assertThat(routing(false, "2026-10-05T06:00:00Z").historicalTarget(storeId)).isEmpty();
        verifyNoInteractions(stores, history);
    }

    @Test
    void noBaselineOrMidweekBaselineKeepsTheExplicitLegacyPath() {
        selected();
        var routing = routing(true, "2026-10-05T06:00:00Z");
        when(history.authoritativeFrom(storeId)).thenReturn(Optional.empty());
        assertThat(routing.historicalTarget(storeId)).isEmpty();
        when(history.authoritativeFrom(storeId)).thenReturn(Optional.of(Instant.parse("2026-09-22T00:00:00Z")));
        assertThat(routing.historicalTarget(storeId)).isEmpty();
    }

    @Test
    void bothWeeksMustStartWithinTheApprovedHistoryInTheStoreZone() {
        selected();
        when(history.authoritativeFrom(storeId)).thenReturn(Optional.of(Instant.parse("2026-09-20T22:00:00Z")));
        var routing = routing(true, "2026-10-04T22:00:00Z");
        var week = routing.historicalTarget(storeId).orElseThrow();
        assertThat(week.start()).hasToString("2026-09-28");
        assertThat(week.zone().getId()).isEqualTo("Europe/Kaliningrad");
        assertThat(routing.stillLatest(week)).isTrue();
        assertThat(routing(true, "2026-10-11T22:00:00Z").stillLatest(week)).isFalse();
    }

    @Test
    void oneInstantAfterPreviousWeekBoundaryIsNotCompleteHistory() {
        selected();
        when(history.authoritativeFrom(storeId)).thenReturn(Optional.of(Instant.parse("2026-09-20T22:00:00.001Z")));
        assertThat(routing(true, "2026-10-04T22:00:00Z").historicalTarget(storeId)).isEmpty();
    }

    private void selected() {
        when(stores.findById(storeId)).thenReturn(Optional.of(store));
        when(store.getTimezone()).thenReturn("Europe/Kaliningrad");
    }

    private SellerWeeklyCurrentRouting routing(boolean enabled, String now) {
        return new SellerWeeklyCurrentRouting(new SellerWeeklyPreparationProperties(enabled,
                Duration.ofMinutes(1), 10, 4, 25, 2, Duration.ofMinutes(1)), stores, history,
                Clock.fixed(Instant.parse(now), ZoneOffset.UTC));
    }
}
