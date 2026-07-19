package com.storeanalytics.sync.service;

import com.storeanalytics.integration.connection.model.IntegrationConnection;
import com.storeanalytics.integration.connection.repository.IntegrationConnectionRepository;
import com.storeanalytics.integration.livesklad.client.LiveSkladClient;
import com.storeanalytics.integration.livesklad.dto.LiveSkladCashItemPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladCashRegisterPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladCashTransactionPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladReturnDetailPayload;
import com.storeanalytics.integration.livesklad.exception.LiveSkladException;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.repository.StoreRepository;
import com.storeanalytics.sync.exception.ReturnSyncCapacityException;
import com.storeanalytics.sync.exception.ReturnSyncException;
import com.storeanalytics.sync.model.SourceSystem;
import com.storeanalytics.sync.model.SyncPeriod;
import com.storeanalytics.sync.model.SyncRun;
import com.storeanalytics.sync.model.SyncRunError;
import com.storeanalytics.sync.model.SyncStatus;
import com.storeanalytics.sync.repository.SyncRunErrorRepository;
import com.storeanalytics.sync.repository.SyncRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ReturnSyncService {

    private static final String LIVESKLAD_CONNECTION_KEY = "livesklad-default";
    private static final String RETURN_CASH_ITEM_TYPE = "saleReturn";
    private static final int MAX_DETAILS_PER_RUN = 70;

    private final LiveSkladClient liveSkladClient;
    private final IntegrationConnectionRepository connectionRepository;
    private final StoreRepository storeRepository;
    private final ReturnSyncPersistence persistence;
    private final SyncRunRepository syncRunRepository;
    private final SyncRunErrorRepository errorRepository;
    private final Clock clock;

    public ReturnSyncService(
            LiveSkladClient liveSkladClient,
            IntegrationConnectionRepository connectionRepository,
            StoreRepository storeRepository,
            ReturnSyncPersistence persistence,
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

    public ReturnSyncResult synchronize(ReturnSyncPeriod period) {
        IntegrationConnection connection = activeLiveSkladConnection();
        List<Store> stores = storeRepository
                .findAllByConnectionIdAndActiveTrueOrderByExternalId(connection.getId());
        if (stores.isEmpty()) {
            throw new IllegalStateException(
                    "No active LiveSklad stores found; synchronize stores first"
            );
        }
        SyncRun syncRun = syncRunRepository.save(SyncRun.startReturnSync(
                connection,
                new SyncPeriod(period.start(), period.end()),
                clock.instant()
        ));
        int fetched = 0;
        try {
            List<LiveSkladCashItemPayload> cashItems =
                    liveSkladClient.fetchCashItems();
            List<LiveSkladCashItemPayload> returnCashItems = cashItems.stream()
                    .filter(this::isReturnCashItem)
                    .toList();
            if (returnCashItems.isEmpty()) {
                throw new IllegalStateException(
                        "LiveSklad cash-item dictionary has no saleReturn item"
                );
            }
            if (returnCashItems.stream().anyMatch(LiveSkladCashItemPayload::income)) {
                throw new IllegalStateException(
                        "LiveSklad saleReturn cash item must be an outflow"
                );
            }

            Discovery discovery = discoverTransactions(
                    stores,
                    returnCashItems,
                    period
            );
            fetched = discovery.activeDocumentCount()
                    + discovery.deletedDocumentCount();
            if (discovery.activeDocumentCount() > MAX_DETAILS_PER_RUN) {
                failSyncRun(
                        syncRun,
                        fetched,
                        "Return synchronization window exceeds safe detail limit",
                        false
                );
                throw new ReturnSyncCapacityException(
                        syncRun.getId(),
                        discovery.activeDocumentCount(),
                        MAX_DETAILS_PER_RUN
                );
            }

            List<StoreReturnBatch> batches = loadDetails(discovery);
            ReturnSyncBatchResult batch = persistence.synchronize(
                    syncRun.getId(),
                    period,
                    cashItems,
                    batches
            );
            if (batch.unresolvedDocuments() > 0) {
                syncRun.completePartial(
                        fetched,
                        batch.documentsCreated(),
                        batch.documentsUpdated(),
                        batch.documentsSkipped(),
                        batch.unresolvedDocuments(),
                        clock.instant()
                );
            } else {
                syncRun.complete(
                        fetched,
                        batch.documentsCreated(),
                        batch.documentsUpdated(),
                        batch.documentsSkipped(),
                        clock.instant()
                );
            }
            return ReturnSyncResult.from(syncRunRepository.save(syncRun), batch);
        } catch (ReturnSyncCapacityException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            failSyncRun(
                    syncRun,
                    fetched,
                    "Return synchronization failed: "
                            + exception.getClass().getSimpleName(),
                    exception instanceof LiveSkladException
            );
            throw new ReturnSyncException(syncRun.getId(), exception);
        }
    }

    private Discovery discoverTransactions(
            List<Store> stores,
            List<LiveSkladCashItemPayload> returnCashItems,
            ReturnSyncPeriod period
    ) {
        List<StoreTransactionDiscovery> storeDiscoveries = new ArrayList<>();
        Set<String> registerIds = new HashSet<>();
        Set<String> transactionIds = new HashSet<>();
        Map<String, String> documentStores = new HashMap<>();
        int activeDocuments = 0;
        int deletedDocuments = 0;

        for (Store store : stores) {
            List<LiveSkladCashRegisterPayload> registers =
                    liveSkladClient.fetchCashRegisters(store.getExternalId());
            Map<String, List<LiveSkladCashTransactionPayload>> byDocument =
                    new LinkedHashMap<>();
            for (LiveSkladCashRegisterPayload register : registers) {
                validateRegister(store, register);
                if (!registerIds.add(register.externalId())) {
                    throw new IllegalStateException(
                            "LiveSklad cash registers contain a duplicate company-wide ID"
                    );
                }
                for (LiveSkladCashItemPayload cashItem : returnCashItems) {
                    List<LiveSkladCashTransactionPayload> transactions =
                            liveSkladClient.fetchCashTransactions(
                                    register.externalId(),
                                    cashItem.externalId(),
                                    period.start(),
                                    period.end()
                            );
                    for (LiveSkladCashTransactionPayload transaction : transactions) {
                        validateTransaction(store, register, cashItem, period, transaction);
                        if (!transactionIds.add(transaction.externalId())) {
                            throw new IllegalStateException(
                                    "LiveSklad cash transactions contain a duplicate ID"
                            );
                        }
                        String previousStore = documentStores.putIfAbsent(
                                transaction.documentExternalId(),
                                store.getExternalId()
                        );
                        if (previousStore != null
                                && !previousStore.equals(store.getExternalId())) {
                            throw new IllegalStateException(
                                    "LiveSklad return document is referenced by multiple stores"
                            );
                        }
                        byDocument.computeIfAbsent(
                                transaction.documentExternalId(),
                                ignored -> new ArrayList<>()
                        ).add(transaction);
                    }
                }
            }
            for (List<LiveSkladCashTransactionPayload> transactions
                    : byDocument.values()) {
                boolean deleted = transactions.stream()
                        .allMatch(LiveSkladCashTransactionPayload::deleted);
                if (deleted) {
                    deletedDocuments++;
                } else {
                    activeDocuments++;
                }
            }
            storeDiscoveries.add(new StoreTransactionDiscovery(
                    store,
                    registers,
                    byDocument
            ));
        }
        return new Discovery(
                storeDiscoveries,
                activeDocuments,
                deletedDocuments
        );
    }

    private List<StoreReturnBatch> loadDetails(Discovery discovery) {
        List<StoreReturnBatch> batches = new ArrayList<>();
        for (StoreTransactionDiscovery storeDiscovery
                : discovery.storeDiscoveries()) {
            List<LiveSkladReturnSource> sources = new ArrayList<>();
            for (Map.Entry<String, List<LiveSkladCashTransactionPayload>> entry
                    : storeDiscovery.transactionsByDocument().entrySet()) {
                boolean deleted = entry.getValue().stream()
                        .allMatch(LiveSkladCashTransactionPayload::deleted);
                LiveSkladReturnDetailPayload detail = deleted
                        ? null
                        : liveSkladClient.fetchReturnDetail(entry.getKey());
                sources.add(new LiveSkladReturnSource(entry.getValue(), detail));
            }
            batches.add(new StoreReturnBatch(
                    storeDiscovery.store(),
                    storeDiscovery.cashRegisters(),
                    sources
            ));
        }
        return List.copyOf(batches);
    }

    private boolean isReturnCashItem(LiveSkladCashItemPayload cashItem) {
        return RETURN_CASH_ITEM_TYPE.equalsIgnoreCase(cashItem.sourceType());
    }

    private void validateRegister(
            Store store,
            LiveSkladCashRegisterPayload register
    ) {
        if (!store.getExternalId().equals(register.storeExternalId())) {
            throw new IllegalArgumentException(
                    "LiveSklad cash register belongs to another store"
            );
        }
    }

    private void validateTransaction(
            Store store,
            LiveSkladCashRegisterPayload register,
            LiveSkladCashItemPayload cashItem,
            ReturnSyncPeriod period,
            LiveSkladCashTransactionPayload transaction
    ) {
        if (!store.getExternalId().equals(transaction.storeExternalId())
                || !register.externalId().equals(
                transaction.cashRegisterExternalId())
                || !cashItem.externalId().equals(
                transaction.cashItemExternalId())
                || !RETURN_CASH_ITEM_TYPE.equalsIgnoreCase(
                transaction.cashItemType())
                || transaction.cashItemIncome()
                || !StringUtils.hasText(transaction.documentExternalId())) {
            throw new IllegalArgumentException(
                    "LiveSklad return cash transaction is inconsistent"
            );
        }
        if (transaction.occurredAt().isBefore(period.start())
                || transaction.occurredAt().isAfter(period.end())) {
            throw new IllegalArgumentException(
                    "LiveSklad return cash transaction is outside the requested period"
            );
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
        if (syncRun.getStatus() != SyncStatus.RUNNING) {
            return;
        }
        Instant now = clock.instant();
        syncRun.fail(fetched, summary, now);
        SyncRun failedRun = syncRunRepository.save(syncRun);
        errorRepository.save(SyncRunError.returnSyncFailure(
                failedRun,
                summary,
                retryable,
                now
        ));
    }

    private record StoreTransactionDiscovery(
            Store store,
            List<LiveSkladCashRegisterPayload> cashRegisters,
            Map<String, List<LiveSkladCashTransactionPayload>>
                    transactionsByDocument
    ) {

        private StoreTransactionDiscovery {
            cashRegisters = List.copyOf(cashRegisters);
            Map<String, List<LiveSkladCashTransactionPayload>> copy =
                    new LinkedHashMap<>();
            transactionsByDocument.forEach(
                    (key, value) -> copy.put(key, List.copyOf(value))
            );
            transactionsByDocument = Map.copyOf(copy);
        }
    }

    private record Discovery(
            List<StoreTransactionDiscovery> storeDiscoveries,
            int activeDocumentCount,
            int deletedDocumentCount
    ) {

        private Discovery {
            storeDiscoveries = List.copyOf(storeDiscoveries);
        }
    }
}
