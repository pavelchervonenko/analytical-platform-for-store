package com.storeanalytics.sync.service;

import com.storeanalytics.integration.connection.model.IntegrationConnection;
import com.storeanalytics.integration.connection.repository.IntegrationConnectionRepository;
import com.storeanalytics.integration.livesklad.client.LiveSkladClient;
import com.storeanalytics.integration.livesklad.dto.LiveSkladStorePayload;
import com.storeanalytics.sync.exception.StoreSyncException;
import com.storeanalytics.sync.model.SourceSystem;
import com.storeanalytics.sync.model.SyncRun;
import com.storeanalytics.sync.model.SyncRunError;
import com.storeanalytics.sync.model.SyncScope;
import com.storeanalytics.sync.repository.SyncRunErrorRepository;
import com.storeanalytics.sync.repository.SyncRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class StoreSyncService {

    private static final String LIVESKLAD_CONNECTION_KEY = "livesklad-default";

    private final LiveSkladClient liveSkladClient;
    private final IntegrationConnectionRepository connectionRepository;
    private final StoreSyncPersistence persistence;
    private final SyncRunRepository syncRunRepository;
    private final SyncRunErrorRepository errorRepository;
    private final Clock clock;
    private final SyncMetrics syncMetrics;

    public StoreSyncService(
            LiveSkladClient liveSkladClient,
            IntegrationConnectionRepository connectionRepository,
            StoreSyncPersistence persistence,
            SyncRunLifecycle lifecycle
    ) {
        this.liveSkladClient = liveSkladClient;
        this.connectionRepository = connectionRepository;
        this.persistence = persistence;
        this.syncRunRepository = lifecycle.runs();
        this.errorRepository = lifecycle.errors();
        this.clock = lifecycle.clock();
        this.syncMetrics = lifecycle.metrics();
    }

    public StoreSyncResult synchronize() {
        return synchronize(SyncExecutionContext.manual());
    }

    public StoreSyncResult synchronize(SyncExecutionContext context) {
        return syncMetrics.record(
                SyncScope.STORES,
                context.triggerType(),
                () -> synchronizeInternal(context)
        );
    }

    private StoreSyncResult synchronizeInternal(SyncExecutionContext context) {
        IntegrationConnection connection = connectionRepository
                .findByConnectionKeyAndActiveTrue(LIVESKLAD_CONNECTION_KEY)
                .filter(candidate -> candidate.getSourceSystem() == SourceSystem.LIVESKLAD)
                .orElseThrow(() -> new IllegalStateException(
                        "Active LiveSklad integration connection is not configured"
                ));
        SyncRun syncRun = syncRunRepository.save(SyncRun.startStoreSync(
                connection,
                context.triggerType(),
                context.syncJobId(),
                context.requestedBy(),
                clock.instant()
        ));
        int fetched = 0;
        try {
            List<LiveSkladStorePayload> stores = liveSkladClient.fetchStores();
            fetched = stores.size();
            int created = 0;
            int updated = 0;
            int skipped = 0;
            for (LiveSkladStorePayload store : stores) {
                StoreWriteResult result = persistence.synchronize(syncRun.getId(), store);
                switch (result) {
                    case CREATED -> created++;
                    case UPDATED -> updated++;
                    case SKIPPED -> skipped++;
                    default -> throw new IllegalStateException("Unsupported store write result");
                }
            }
            syncRun.complete(fetched, created, updated, skipped, clock.instant());
            return StoreSyncResult.from(syncRunRepository.save(syncRun));
        } catch (RuntimeException exception) {
            failSyncRun(syncRun, fetched, exception);
            throw new StoreSyncException(syncRun.getId(), exception);
        }
    }

    private void failSyncRun(SyncRun syncRun, int fetched, RuntimeException exception) {
        Instant now = clock.instant();
        String summary = "Store synchronization failed: " + exception.getClass().getSimpleName();
        syncRun.fail(fetched, summary, now);
        SyncRun failedRun = syncRunRepository.save(syncRun);
        errorRepository.save(SyncRunError.storeSyncFailure(failedRun, summary, now));
    }
}
