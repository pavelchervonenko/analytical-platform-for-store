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
import com.storeanalytics.sync.repository.SyncRunErrorRepository;
import com.storeanalytics.sync.repository.SyncRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class EmployeeSyncService {

    private static final String LIVESKLAD_CONNECTION_KEY = "livesklad-default";

    private final LiveSkladClient liveSkladClient;
    private final IntegrationConnectionRepository connectionRepository;
    private final StoreRepository storeRepository;
    private final EmployeeSyncPersistence persistence;
    private final SyncRunRepository syncRunRepository;
    private final SyncRunErrorRepository errorRepository;
    private final Clock clock;

    public EmployeeSyncService(
            LiveSkladClient liveSkladClient,
            IntegrationConnectionRepository connectionRepository,
            StoreRepository storeRepository,
            EmployeeSyncPersistence persistence,
            SyncRunRepository syncRunRepository,
            SyncRunErrorRepository errorRepository,
            Clock clock
    ) {
        this.liveSkladClient = liveSkladClient;
        this.connectionRepository = connectionRepository;
        this.storeRepository = storeRepository;
        this.persistence = persistence;
        this.syncRunRepository = syncRunRepository;
        this.errorRepository = errorRepository;
        this.clock = clock;
    }

    public EmployeeSyncResult synchronize() {
        IntegrationConnection connection = activeLiveSkladConnection();
        List<Store> stores = storeRepository
                .findAllByConnectionIdAndActiveTrueOrderByExternalId(connection.getId());
        if (stores.isEmpty()) {
            throw new IllegalStateException(
                    "No active LiveSklad stores found; synchronize stores first"
            );
        }

        SyncRun syncRun = syncRunRepository.save(
                SyncRun.startEmployeeSync(connection, clock.instant())
        );
        int fetched = 0;
        try {
            List<StoreEmployeeBatch> batches = new ArrayList<>();
            for (Store store : stores) {
                List<LiveSkladEmployeePayload> employees =
                        liveSkladClient.fetchEmployees(store.getExternalId());
                fetched += employees.size();
                batches.add(new StoreEmployeeBatch(store, employees));
            }

            int created = 0;
            int updated = 0;
            int skipped = 0;
            Set<UUID> globallySeenEmployeeIds = new HashSet<>();
            Map<UUID, Set<UUID>> seenByStore = new HashMap<>();
            for (StoreEmployeeBatch batch : batches) {
                Set<UUID> storeEmployeeIds = new HashSet<>();
                for (LiveSkladEmployeePayload employee : batch.employees()) {
                    EmployeeRecordWriteResult result = persistence.synchronize(
                            syncRun.getId(),
                            batch.store().getId(),
                            employee
                    );
                    storeEmployeeIds.add(result.employeeId());
                    globallySeenEmployeeIds.add(result.employeeId());
                    switch (result.outcome()) {
                        case CREATED -> created++;
                        case UPDATED -> updated++;
                        case SKIPPED -> skipped++;
                        default -> throw new IllegalStateException(
                                "Unsupported employee write result"
                        );
                    }
                }
                seenByStore.put(batch.store().getId(), Set.copyOf(storeEmployeeIds));
            }

            int assignmentsDeactivated = 0;
            for (Store store : stores) {
                assignmentsDeactivated += persistence.deactivateMissingAssignments(
                        store.getId(),
                        seenByStore.getOrDefault(store.getId(), Set.of())
                );
            }
            int employeesDeactivated = persistence.deactivateMissingEmployees(
                    connection.getId(),
                    globallySeenEmployeeIds
            );

            syncRun.complete(fetched, created, updated, skipped, clock.instant());
            SyncRun completedRun = syncRunRepository.save(syncRun);
            return EmployeeSyncResult.from(
                    completedRun,
                    assignmentsDeactivated,
                    employeesDeactivated
            );
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
