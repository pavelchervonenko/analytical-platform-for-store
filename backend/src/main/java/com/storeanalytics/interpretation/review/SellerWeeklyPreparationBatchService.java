package com.storeanalytics.interpretation.review;

import com.storeanalytics.interpretation.snapshot.WeeklySnapshotPlanningStore;
import java.time.Clock;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Bounded free work with a resumable store sweep and durable per-week progress. No AI dependency. */
@Service
class SellerWeeklyPreparationBatchService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SellerWeeklyPreparationBatchService.class);
    private final WeeklySnapshotPlanningStore stores;
    private final SellerWeeklyPreparationStore queue;
    private final SellerWeeklyPreparationRunner runner;
    private final SellerWeeklyPreparationProperties properties;
    private final Clock clock;
    private final LongSupplier nanoTime;
    private final String owner = "seller-weekly-free-" + UUID.randomUUID();
    private UUID cursor;
    private int firstPhase;

    @Autowired
    SellerWeeklyPreparationBatchService(WeeklySnapshotPlanningStore stores, SellerWeeklyPreparationStore queue,
            SellerWeeklyPreparationRunner runner, SellerWeeklyPreparationProperties properties, Clock clock) {
        this(stores, queue, runner, properties, clock, System::nanoTime);
    }

    SellerWeeklyPreparationBatchService(WeeklySnapshotPlanningStore stores, SellerWeeklyPreparationStore queue,
            SellerWeeklyPreparationRunner runner, SellerWeeklyPreparationProperties properties, Clock clock,
            LongSupplier nanoTime) {
        this.stores = stores;
        this.queue = queue;
        this.runner = runner;
        this.properties = properties;
        this.clock = clock;
        this.nanoTime = nanoTime;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public synchronized Result reconcile() {
        if (!properties.enabled()) {
            return new Result(0, 0, 0, 0, 0, 0);
        }
        long started = nanoTime.getAsLong();
        int phase = firstPhase;
        firstPhase = (firstPhase + 1) % 3;
        // Rotate all three first phases; slow work must not permanently starve discovery or stale refresh.
        int reopened = phase == 2 ? refresh(started) : 0;
        var discovered = phase != 0 ? discover(started) : new DiscoveryResult(0, 0, 0);
        var preparation = prepare(started);
        if (phase != 2) {
            reopened = refresh(started);
        }
        if (phase == 0) {
            discovered = discover(started);
        }
        return new Result(discovered.scanned(), discovered.inserted(), reopened, preparation.prepared(),
                preparation.waiting(), preparation.failures() + discovered.failures());
    }

    private int refresh(long started) {
        return withinBudget(started)
                ? queue.requeueStaleSnapshots(properties.refreshBatchSize(), clock.instant()) : 0;
    }

    private PreparationResult prepare(long started) {
        int prepared = 0;
        int waiting = 0;
        int failures = 0;
        for (int index = 0; index < properties.preparationBatchSize() && withinBudget(started); index++) {
            var result = runner.prepareNext(owner);
            if ("IDLE".equals(result.state())) {
                break;
            }
            switch (result.state()) {
                case "SUCCEEDED" -> prepared++;
                case "WAITING_SOURCES", "WAITING_HISTORY", "LEASE_LOST" -> waiting++;
                case "FAILED" -> failures++;
                default -> throw new IllegalStateException("Unsupported free preparation state");
            }
        }
        return new PreparationResult(prepared, waiting, failures);
    }

    private DiscoveryResult discover(long started) {
        int scanned = 0;
        int discovered = 0;
        int failures = 0;
        if (withinBudget(started)) {
            var page = stores.activeStoresAfter(cursor, properties.storeBatchSize());
            for (var store : page) {
                if (!withinBudget(started)) {
                    break;
                }
                try {
                    discovered += queue.discover(store.storeId(), clock.instant(), properties.discoveryWeeks())
                            .insertedWeeks();
                } catch (RuntimeException failure) {
                    failures++;
                    LOGGER.error("Seller weekly discovery deferred; store_id={} failure_type={}",
                            store.storeId(), failure.getClass().getSimpleName());
                }
                // One broken store cannot pin the sweep and starve other stores.
                cursor = store.storeId();
                scanned++;
            }
            if (scanned == page.size() && page.size() < properties.storeBatchSize()) {
                cursor = null;
            }
        }
        return new DiscoveryResult(scanned, discovered, failures);
    }

    private boolean withinBudget(long started) {
        return nanoTime.getAsLong() - started < properties.timeBudget().toNanos();
    }

    record Result(int scanned, int discovered, int reopened, int prepared, int waiting, int failures) { }
    private record DiscoveryResult(int scanned, int inserted, int failures) { }
    private record PreparationResult(int prepared, int waiting, int failures) { }
}
