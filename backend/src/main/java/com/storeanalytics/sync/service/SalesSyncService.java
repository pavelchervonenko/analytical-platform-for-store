package com.storeanalytics.sync.service;

import com.storeanalytics.integration.connection.model.IntegrationConnection;
import com.storeanalytics.integration.connection.repository.IntegrationConnectionRepository;
import com.storeanalytics.integration.livesklad.client.LiveSkladClient;
import com.storeanalytics.integration.livesklad.dto.LiveSkladSaleDetailPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladSaleSummaryPayload;
import com.storeanalytics.integration.livesklad.exception.LiveSkladException;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.repository.StoreRepository;
import com.storeanalytics.sync.exception.SalesSyncCapacityException;
import com.storeanalytics.sync.exception.SalesSyncException;
import com.storeanalytics.sync.model.SourceSystem;
import com.storeanalytics.sync.model.SyncPeriod;
import com.storeanalytics.sync.model.SyncRun;
import com.storeanalytics.sync.model.SyncRunError;
import com.storeanalytics.sync.repository.SyncRunErrorRepository;
import com.storeanalytics.sync.repository.SyncRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class SalesSyncService {

    private static final String LIVESKLAD_CONNECTION_KEY = "livesklad-default";
    private static final int MAX_DETAILS_PER_RUN = 70;

    private final LiveSkladClient liveSkladClient;
    private final IntegrationConnectionRepository connectionRepository;
    private final StoreRepository storeRepository;
    private final SalesSyncPersistence persistence;
    private final SyncRunRepository syncRunRepository;
    private final SyncRunErrorRepository errorRepository;
    private final Clock clock;

    public SalesSyncService(
            LiveSkladClient liveSkladClient,
            IntegrationConnectionRepository connectionRepository,
            StoreRepository storeRepository,
            SalesSyncPersistence persistence,
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

    public SalesSyncResult synchronize(SalesSyncPeriod period) {
        IntegrationConnection connection = activeLiveSkladConnection();
        List<Store> stores = storeRepository
                .findAllByConnectionIdAndActiveTrueOrderByExternalId(connection.getId());
        if (stores.isEmpty()) {
            throw new IllegalStateException(
                    "No active LiveSklad stores found; synchronize stores first"
            );
        }

        SyncRun syncRun = syncRunRepository.save(SyncRun.startSalesSync(
                connection,
                new SyncPeriod(period.start(), period.end()),
                clock.instant()
        ));
        int fetched = 0;
        try {
            List<StoreSaleSummaries> summariesByStore = new ArrayList<>();
            Set<String> globallySeenSaleIds = new HashSet<>();
            for (Store store : stores) {
                List<LiveSkladSaleSummaryPayload> summaries = liveSkladClient.fetchSales(
                        store.getExternalId(),
                        period.start(),
                        period.end()
                );
                for (LiveSkladSaleSummaryPayload summary : summaries) {
                    if (!globallySeenSaleIds.add(summary.externalId())) {
                        throw new IllegalStateException(
                                "LiveSklad sales contain a duplicate company-wide ID"
                        );
                    }
                }
                fetched += summaries.size();
                summariesByStore.add(new StoreSaleSummaries(store, summaries));
            }
            if (fetched > MAX_DETAILS_PER_RUN) {
                failSyncRun(
                        syncRun,
                        fetched,
                        "Sales synchronization window exceeds safe detail limit",
                        false
                );
                throw new SalesSyncCapacityException(
                        syncRun.getId(),
                        fetched,
                        MAX_DETAILS_PER_RUN
                );
            }

            List<StoreSalesBatch> batches = new ArrayList<>();
            for (StoreSaleSummaries storeSummaries : summariesByStore) {
                List<LiveSkladSaleSource> sources = new ArrayList<>();
                for (LiveSkladSaleSummaryPayload summary : storeSummaries.summaries()) {
                    LiveSkladSaleDetailPayload detail =
                            liveSkladClient.fetchSaleDetail(summary.externalId());
                    sources.add(new LiveSkladSaleSource(summary, detail));
                }
                batches.add(new StoreSalesBatch(storeSummaries.store(), sources));
            }

            SalesSyncBatchResult batch = persistence.synchronize(
                    syncRun.getId(),
                    period,
                    batches
            );
            syncRun.complete(
                    fetched,
                    batch.documentsCreated(),
                    batch.documentsUpdated(),
                    batch.documentsSkipped(),
                    clock.instant()
            );
            return SalesSyncResult.from(syncRunRepository.save(syncRun), batch);
        } catch (SalesSyncCapacityException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            failSyncRun(
                    syncRun,
                    fetched,
                    "Sales synchronization failed: "
                            + exception.getClass().getSimpleName(),
                    exception instanceof LiveSkladException
            );
            throw new SalesSyncException(syncRun.getId(), exception);
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

    private void failSyncRun(
            SyncRun syncRun,
            int fetched,
            String summary,
            boolean retryable
    ) {
        if (syncRun.getStatus() != com.storeanalytics.sync.model.SyncStatus.RUNNING) {
            return;
        }
        Instant now = clock.instant();
        syncRun.fail(fetched, summary, now);
        SyncRun failedRun = syncRunRepository.save(syncRun);
        errorRepository.save(SyncRunError.salesSyncFailure(
                failedRun,
                summary,
                retryable,
                now
        ));
    }

    private record StoreSaleSummaries(
            Store store,
            List<LiveSkladSaleSummaryPayload> summaries
    ) {

        private StoreSaleSummaries {
            summaries = List.copyOf(summaries);
        }
    }
}
