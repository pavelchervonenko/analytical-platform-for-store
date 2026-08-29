package com.storeanalytics.interpretation.review;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse.DateRange;
import com.storeanalytics.interpretation.snapshot.WeeklySnapshotPlanningStore;
import com.storeanalytics.interpretation.snapshot.WeeklySnapshotPlanningStore.SourceSync;
import com.storeanalytics.interpretation.snapshot.WeeklySnapshotPlanningStore.StoreTarget;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class WeeklyReviewSnapshotPlanningService {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            WeeklyReviewSnapshotPlanningService.class
    );

    private final WeeklySnapshotPlanningStore sourceStore;
    private final WeeklyReviewSnapshotStore snapshotStore;
    private final WeeklyReviewService reviewService;
    private final WeeklyReviewSnapshotPlannerProperties properties;
    private final Clock clock;
    private final WeeklyReviewPolicyV1 policy = new WeeklyReviewPolicyV1();

    public WeeklyReviewSnapshotPlanningService(
            WeeklySnapshotPlanningStore sourceStore,
            WeeklyReviewSnapshotStore snapshotStore,
            WeeklyReviewService reviewService,
            WeeklyReviewSnapshotPlannerProperties properties,
            Clock clock
    ) {
        this.sourceStore = sourceStore;
        this.snapshotStore = snapshotStore;
        this.reviewService = reviewService;
        this.properties = properties;
        this.clock = clock;
    }

    public WeeklyReviewSnapshotPlanningResult plan() {
        Instant now = clock.instant();
        Counters counters = new Counters();
        UUID cursor = null;
        while (true) {
            List<StoreTarget> stores = sourceStore.activeStoresAfter(
                    cursor, properties.batchSize()
            );
            if (stores.isEmpty()) {
                break;
            }
            for (StoreTarget store : stores) {
                counters.storesScanned++;
                try {
                    counters.record(planStore(store, now));
                } catch (DateTimeException exception) {
                    counters.invalidStores++;
                    LOGGER.error(
                            "Weekly review snapshot skipped invalid timezone; storeId={}",
                            store.storeId()
                    );
                } catch (RuntimeException exception) {
                    counters.failures++;
                    LOGGER.error(
                            "Weekly review snapshot planning failed; storeId={}, failureType={}",
                            store.storeId(),
                            exception.getClass().getSimpleName()
                    );
                }
            }
            cursor = stores.getLast().storeId();
            if (stores.size() < properties.batchSize()) {
                break;
            }
        }
        return counters.result();
    }

    private PlanningOutcome planStore(StoreTarget store, Instant now) {
        ZoneId zone = ZoneId.of(store.timezone());
        var period = policy.period(now, store.timezone());
        DateRange current = period.current();
        Instant requiredCoverageStart = period.previous()
                .start()
                .atStartOfDay(zone)
                .toInstant();
        Instant requiredCoverageEnd = current.end()
                .plusDays(1)
                .atStartOfDay(zone)
                .toInstant();
        Optional<SourceSync> source = sourceStore.newestSuitableSource(
                store.storeId(),
                requiredCoverageStart,
                requiredCoverageEnd,
                now
        );
        if (source.isEmpty()) {
            return PlanningOutcome.SOURCE_UNAVAILABLE;
        }
        Optional<PersistedWeeklyReviewSnapshot> latest = snapshotStore.findLatest(
                store.storeId(), current
        );
        if (latest.isPresent() && sourceUnchanged(latest.get(), source.get())) {
            return PlanningOutcome.SOURCE_UNCHANGED;
        }

        PersistedWeeklyReviewSnapshot generated = reviewService.generate(
                store.storeId()
        );
        if (latest.isEmpty()) {
            return PlanningOutcome.SNAPSHOT_CREATED;
        }
        return generated.id().equals(latest.get().id())
                ? PlanningOutcome.CONTENT_REUSED
                : PlanningOutcome.REVISION_CREATED;
    }

    private boolean sourceUnchanged(
            PersistedWeeklyReviewSnapshot latest,
            SourceSync source
    ) {
        Instant sourceUpdatedAt = latest.response()
                .provenance()
                .sourceDataUpdatedAt();
        return sourceUpdatedAt != null
                && !source.completedAt().isAfter(sourceUpdatedAt);
    }

    private enum PlanningOutcome {
        SNAPSHOT_CREATED,
        REVISION_CREATED,
        CONTENT_REUSED,
        SOURCE_UNAVAILABLE,
        SOURCE_UNCHANGED
    }

    private static final class Counters {

        private int storesScanned;
        private int snapshotsCreated;
        private int revisionsCreated;
        private int contentReused;
        private int sourceUnavailable;
        private int sourceUnchanged;
        private int invalidStores;
        private int failures;

        private void record(PlanningOutcome outcome) {
            switch (outcome) {
                case SNAPSHOT_CREATED -> snapshotsCreated++;
                case REVISION_CREATED -> revisionsCreated++;
                case CONTENT_REUSED -> contentReused++;
                case SOURCE_UNAVAILABLE -> sourceUnavailable++;
                case SOURCE_UNCHANGED -> sourceUnchanged++;
                default -> throw new IllegalStateException(
                        "Unsupported weekly review planning outcome"
                );
            }
        }

        private WeeklyReviewSnapshotPlanningResult result() {
            return new WeeklyReviewSnapshotPlanningResult(
                    storesScanned,
                    snapshotsCreated,
                    revisionsCreated,
                    contentReused,
                    sourceUnavailable,
                    sourceUnchanged,
                    invalidStores,
                    failures
            );
        }
    }
}
