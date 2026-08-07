package com.storeanalytics.interpretation.snapshot;

import com.storeanalytics.interpretation.config.WeeklySnapshotPlannerProperties;
import com.storeanalytics.interpretation.snapshot.WeeklySnapshotPlanningStore.LatestSnapshot;
import com.storeanalytics.interpretation.snapshot.WeeklySnapshotPlanningStore.SourceSync;
import com.storeanalytics.interpretation.snapshot.WeeklySnapshotPlanningStore.StoreTarget;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class WeeklySnapshotPlanningService {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            WeeklySnapshotPlanningService.class
    );

    private final WeeklySnapshotPlanningStore planningStore;
    private final WeeklySnapshotJobStore jobStore;
    private final WeeklySnapshotPlannerProperties properties;
    private final Clock clock;

    public WeeklySnapshotPlanningService(
            WeeklySnapshotPlanningStore planningStore,
            WeeklySnapshotJobStore jobStore,
            WeeklySnapshotPlannerProperties properties,
            Clock clock
    ) {
        this.planningStore = planningStore;
        this.jobStore = jobStore;
        this.properties = properties;
        this.clock = clock;
    }

    public WeeklySnapshotPlanningResult plan() {
        Instant now = clock.instant();
        Counters counters = new Counters();
        for (StoreTarget store : planningStore.activeStores(properties.batchSize())) {
            counters.storesScanned++;
            try {
                counters.record(planStore(store, now));
            } catch (DateTimeException exception) {
                counters.invalidStores++;
                LOGGER.error(
                        "Weekly snapshot planning skipped store with invalid timezone; storeId={}",
                        store.storeId(),
                        exception
                );
            } catch (WeeklySnapshotJobConflictException exception) {
                counters.conflicts++;
                LOGGER.debug(
                        "Weekly snapshot planning conflict will be reconciled; storeId={}",
                        store.storeId(),
                        exception
                );
            }
        }
        return counters.result();
    }

    private PlanningOutcome planStore(StoreTarget store, Instant now) {
        ZoneId zone = ZoneId.of(store.timezone());
        LocalDate currentWeekStart = LocalDate.ofInstant(now, zone)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        StoreKpiPeriod period = new StoreKpiPeriod(
                currentWeekStart.minusWeeks(1),
                currentWeekStart.minusDays(1)
        );
        Instant requiredCoverage = currentWeekStart.atStartOfDay(zone).toInstant();
        Optional<SourceSync> source = planningStore.newestSuitableSource(
                store.storeId(),
                requiredCoverage,
                now
        );
        if (source.isEmpty()) {
            return PlanningOutcome.SOURCE_UNAVAILABLE;
        }

        Optional<LatestSnapshot> latest = planningStore.latestSnapshot(
                store.storeId(),
                period.start(),
                period.end()
        );
        WeeklySnapshotJobType type = WeeklySnapshotJobType.INITIAL;
        java.util.UUID baseSnapshotId = null;
        if (latest.isPresent()) {
            Instant revisionDeadline = requiredCoverage.plus(properties.revisionWindow());
            if (!now.isBefore(revisionDeadline)) {
                return PlanningOutcome.REVISION_WINDOW_CLOSED;
            }
            LatestSnapshot base = latest.get();
            if (!source.get().completedAt().isAfter(base.sourceDataCutoff())) {
                return PlanningOutcome.SOURCE_UNCHANGED;
            }
            type = WeeklySnapshotJobType.AUTO_REVISION;
            baseSnapshotId = base.snapshotId();
        }

        SourceSync selectedSource = source.get();
        WeeklySnapshotJobRequest request = new WeeklySnapshotJobRequest(
                store.storeId(),
                null,
                type,
                period,
                store.timezone(),
                selectedSource.syncJobId(),
                selectedSource.completedAt(),
                WeeklySnapshotPolicyV2.VERSIONS,
                baseSnapshotId,
                properties.maxAttempts()
        );
        if (jobStore.requestExists(request)) {
            return PlanningOutcome.ALREADY_PLANNED;
        }
        jobStore.enqueue(request, now);
        return PlanningOutcome.REQUEST_ACCEPTED;
    }

    private enum PlanningOutcome {
        REQUEST_ACCEPTED,
        ALREADY_PLANNED,
        SOURCE_UNAVAILABLE,
        REVISION_WINDOW_CLOSED,
        SOURCE_UNCHANGED
    }

    private static final class Counters {

        private int storesScanned;
        private int requestsAccepted;
        private int alreadyPlanned;
        private int sourceUnavailable;
        private int revisionWindowClosed;
        private int sourceUnchanged;
        private int conflicts;
        private int invalidStores;

        private void record(PlanningOutcome outcome) {
            switch (outcome) {
                case REQUEST_ACCEPTED -> requestsAccepted++;
                case ALREADY_PLANNED -> alreadyPlanned++;
                case SOURCE_UNAVAILABLE -> sourceUnavailable++;
                case REVISION_WINDOW_CLOSED -> revisionWindowClosed++;
                case SOURCE_UNCHANGED -> sourceUnchanged++;
                default -> throw new IllegalStateException("Unsupported planning outcome");
            }
        }

        private WeeklySnapshotPlanningResult result() {
            return new WeeklySnapshotPlanningResult(
                    storesScanned,
                    requestsAccepted,
                    alreadyPlanned,
                    sourceUnavailable,
                    revisionWindowClosed,
                    sourceUnchanged,
                    conflicts,
                    invalidStores
            );
        }
    }
}
