package com.storeanalytics.sync.service;

import com.storeanalytics.integration.connection.model.IntegrationConnection;
import com.storeanalytics.integration.connection.repository.IntegrationConnectionRepository;
import com.storeanalytics.integration.livesklad.client.LiveSkladClient;
import com.storeanalytics.integration.livesklad.dto.LiveSkladEmployeePayload;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.repository.StoreRepository;
import com.storeanalytics.sync.exception.EmployeeSyncException;
import com.storeanalytics.sync.model.SourceSystem;
import com.storeanalytics.sync.model.SyncRun;
import com.storeanalytics.sync.model.SyncRunError;
import com.storeanalytics.sync.model.SyncScope;
import com.storeanalytics.sync.repository.SyncRunErrorRepository;
import com.storeanalytics.sync.repository.SyncRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class EmployeeSyncService {

    private static final String LIVESKLAD_CONNECTION_KEY = "livesklad-default";

    private final LiveSkladClient liveSkladClient;
    private final IntegrationConnectionRepository connectionRepository;
    private final StoreRepository storeRepository;
    private final EmployeeSyncBatchApplier applier;
    private final SyncRunRepository syncRunRepository;
    private final SyncRunErrorRepository errorRepository;
    private final Clock clock;
    private final SyncMetrics syncMetrics;

    public EmployeeSyncService(
            LiveSkladClient liveSkladClient,
            IntegrationConnectionRepository connectionRepository,
            StoreRepository storeRepository,
            EmployeeSyncBatchApplier applier,
            SyncRunLifecycle lifecycle
    ) {
        this.liveSkladClient = liveSkladClient;
        this.connectionRepository = connectionRepository;
        this.storeRepository = storeRepository;
        this.applier = applier;
        this.syncRunRepository = lifecycle.runs();
        this.errorRepository = lifecycle.errors();
        this.clock = lifecycle.clock();
        this.syncMetrics = lifecycle.metrics();
    }

    public EmployeeSyncResult synchronize() {
        return synchronize(SyncExecutionContext.manual());
    }

    public EmployeeSyncResult synchronize(SyncExecutionContext context) {
        return syncMetrics.record(
                SyncScope.EMPLOYEES,
                context.triggerType(),
                () -> synchronizeInternal(context)
        );
    }

    private EmployeeSyncResult synchronizeInternal(SyncExecutionContext context) {
        IntegrationConnection connection = activeLiveSkladConnection();
        List<Store> stores = storeRepository
                .findAllByConnectionIdAndActiveTrueOrderByExternalId(connection.getId());
        if (stores.isEmpty()) {
            throw new IllegalStateException(
                    "No active LiveSklad stores found; synchronize stores first"
            );
        }

        SyncRun syncRun = syncRunRepository.save(SyncRun.startEmployeeSync(
                connection,
                context.triggerType(),
                context.syncJobId(),
                context.requestedBy(),
                clock.instant()
        ));
        int fetched = 0;
        try {
            List<StoreEmployeeBatch> batches = new ArrayList<>();
            for (Store store : stores) {
                List<LiveSkladEmployeePayload> employees =
                        liveSkladClient.fetchEmployees(store.getExternalId());
                fetched += employees.size();
                batches.add(new StoreEmployeeBatch(store, employees));
            }

            return applier.apply(connection.getId(), syncRun.getId(), context, batches);
        } catch (RuntimeException exception) {
            failSyncRun(syncRun, fetched, exception);
            throw new EmployeeSyncException(syncRun.getId(), exception);
        }
    }

    private IntegrationConnection activeLiveSkladConnection() {
        return connectionRepository
                .findByConnectionKeyAndActiveTrue(LIVESKLAD_CONNECTION_KEY)
                .filter(candidate -> candidate.getSourceSystem() == SourceSystem.LIVESKLAD)
                .orElseThrow(() -> new IllegalStateException(
                        "Active LiveSklad integration connection is not configured"
                ));
    }

    private void failSyncRun(SyncRun syncRun, int fetched, RuntimeException exception) {
        Instant now = clock.instant();
        String summary = "Employee synchronization failed: "
                + exception.getClass().getSimpleName();
        syncRun.fail(fetched, summary, now);
        SyncRun failedRun = syncRunRepository.save(syncRun);
        errorRepository.save(SyncRunError.employeeSyncFailure(failedRun, summary, now));
    }
}
