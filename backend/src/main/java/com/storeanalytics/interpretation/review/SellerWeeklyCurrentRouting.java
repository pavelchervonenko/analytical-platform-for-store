package com.storeanalytics.interpretation.review;

import com.storeanalytics.metrics.exception.StoreNotFoundException;
import com.storeanalytics.metrics.repository.SellerMembershipHistoryRepository;
import com.storeanalytics.store.repository.StoreRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Forward-only current-reader cutover; preparation opt-in never invents membership history. */
@Service
class SellerWeeklyCurrentRouting {
    private final SellerWeeklyPreparationProperties preparation;
    private final StoreRepository stores;
    private final SellerMembershipHistoryRepository membership;
    private final Clock clock;

    SellerWeeklyCurrentRouting(SellerWeeklyPreparationProperties preparation, StoreRepository stores,
            SellerMembershipHistoryRepository membership, Clock clock) {
        this.preparation = preparation;
        this.stores = stores;
        this.membership = membership;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<ClosedSellerWeek> historicalTarget(UUID storeId) {
        if (!preparation.enabled()) {
            return Optional.empty();
        }
        var store = stores.findById(storeId).orElseThrow(() -> new StoreNotFoundException(storeId));
        var week = ClosedSellerWeek.latest(clock.instant(), store.getTimezone());
        return membership.authoritativeFrom(storeId).filter(week::hasAuthoritativeComparison).map(value -> week);
    }

    public boolean stillLatest(ClosedSellerWeek week) {
        return week.equals(ClosedSellerWeek.latest(clock.instant(), week.zone().getId()));
    }
}
