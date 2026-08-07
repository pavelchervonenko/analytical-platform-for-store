package com.storeanalytics.interpretation.snapshot;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import com.storeanalytics.metrics.service.StoreKpiPeriod;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class WeeklySnapshotJobExecutionService {

    private final WeeklyAnalyticsFactsSource factsSource;
    private final WeeklySnapshotDraftBuilder draftBuilder;
    private final WeeklySnapshotStore snapshotStore;
    private final WeeklySnapshotJobStore jobStore;
    private final WeeklySnapshotSourceSyncReader sourceSyncReader;
    private final Clock clock;
    private final WeeklySnapshotJobControlStore controlStore;

    public WeeklySnapshotJobExecutionService(
            WeeklyAnalyticsFactsSource factsSource,
            WeeklySnapshotDraftBuilder draftBuilder,
            WeeklySnapshotStore snapshotStore,
            WeeklySnapshotJobStore jobStore,
            WeeklySnapshotSourceSyncReader sourceSyncReader,
            WeeklySnapshotJobControlStore controlStore,
            Clock clock
    ) {
        this.factsSource = factsSource;
        this.draftBuilder = draftBuilder;
        this.snapshotStore = snapshotStore;
        this.jobStore = jobStore;
        this.sourceSyncReader = sourceSyncReader;
        this.clock = clock;
        this.controlStore = controlStore;
    }

    public WeeklySnapshotJob execute(WeeklySnapshotJob job, String owner) {
        WeeklySnapshotJob claimed = requireNonNull(job, "job");
        String leaseOwner = requireText(owner, "owner");
        require(claimed.status() == WeeklySnapshotJobStatus.RUNNING,
                "snapshot job must be RUNNING");
        require(leaseOwner.equals(claimed.leaseOwner()),
                "snapshot job lease is owned elsewhere");

        cancellationCheckpoint(claimed.id());
        WeeklyAnalyticsFactsQuery query = query(claimed);
        WeeklyAnalyticsFacts facts = factsSource.load(query);
        cancellationCheckpoint(claimed.id());
        WeeklySnapshotDraft draft = draftBuilder.build(facts, claimed.timezone());
        require(draft.storeId().equals(claimed.storeId()),
                "snapshot draft belongs to another store");
        require(draft.query().equals(query),
                "snapshot draft belongs to another query");
        require(draft.versions().equals(claimed.versions()),
                "snapshot draft versions differ from the claimed job");
        cancellationCheckpoint(claimed.id());

        Instant syncCompletedAt = sourceSyncReader.completedAt(
                claimed.storeId(),
                claimed.sourceSyncJobId()
        );
        cancellationCheckpoint(claimed.id());
        WeeklySnapshotWriteResult result = snapshotStore.persist(
                new WeeklySnapshotPersistenceCommand(
                        draft,
                        claimed.sourceSyncJobId(),
                        syncCompletedAt,
                        claimed.sourceDataCutoff(),
                        revisionReason(claimed.jobType()),
                        null
                )
        );
        return jobStore.complete(
                claimed.id(),
                leaseOwner,
                result,
                clock.instant()
        );
    }

    private void cancellationCheckpoint(UUID jobId) {
        if (controlStore.cancellationRequested(jobId)) {
            throw new WeeklySnapshotJobCancellationException();
        }
    }

    private WeeklyAnalyticsFactsQuery query(WeeklySnapshotJob job) {
        StoreKpiPeriod period = job.period();
        return new WeeklyAnalyticsFactsQuery(
                job.storeId(),
                period,
                new StoreKpiPeriod(
                        period.start().minusDays(7),
                        period.end().minusDays(7)
                )
        );
    }

    private WeeklySnapshotRevisionReason revisionReason(WeeklySnapshotJobType jobType) {
        return switch (jobType) {
            case INITIAL, AUTO_REVISION -> WeeklySnapshotRevisionReason.AUTO_REVISION;
            case MANUAL_BACKFILL -> WeeklySnapshotRevisionReason.MANUAL_BACKFILL;
        };
    }
}
