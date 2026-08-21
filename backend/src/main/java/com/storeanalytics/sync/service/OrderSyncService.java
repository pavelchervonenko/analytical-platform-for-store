package com.storeanalytics.sync.service;

import com.storeanalytics.integration.connection.model.IntegrationConnection;
import com.storeanalytics.integration.connection.repository.IntegrationConnectionRepository;
import com.storeanalytics.integration.livesklad.client.LiveSkladOrderClient;
import com.storeanalytics.integration.livesklad.dto.LiveSkladOrderDetailPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladOrderSummaryPayload;
import com.storeanalytics.integration.livesklad.exception.LiveSkladException;
import com.storeanalytics.integration.livesklad.exception.LiveSkladOrderChangedException;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.repository.StoreRepository;
import com.storeanalytics.sync.exception.OrderSyncCapacityException;
import com.storeanalytics.sync.exception.OrderSyncException;
import com.storeanalytics.sync.model.SourceSystem;
import com.storeanalytics.sync.model.SyncPeriod;
import com.storeanalytics.sync.model.SyncRun;
import com.storeanalytics.sync.model.SyncRunError;
import com.storeanalytics.sync.model.SyncScope;
import com.storeanalytics.sync.model.SyncStatus;
import com.storeanalytics.sync.model.SyncTriggerType;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class OrderSyncService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(OrderSyncService.class);
    private static final String LIVESKLAD_CONNECTION_KEY = "livesklad-default";
    private static final int MAX_DETAILS_PER_RUN = 70;

    private final LiveSkladOrderClient liveSkladClient;
    private final IntegrationConnectionRepository connectionRepository;
    private final StoreRepository storeRepository;
    private final OrderSyncPersistence persistence;
    private final SyncRunRepository syncRunRepository;
    private final SyncRunErrorRepository errorRepository;
    private final Clock clock;
    private final SyncMetrics syncMetrics;

    public OrderSyncService(
            LiveSkladOrderClient liveSkladClient,
            IntegrationConnectionRepository connectionRepository,
            StoreRepository storeRepository,
            OrderSyncPersistence persistence,
            SyncRunLifecycle lifecycle
    ) {
        this.liveSkladClient = liveSkladClient;
        this.connectionRepository = connectionRepository;
        this.storeRepository = storeRepository;
        this.persistence = persistence;
        this.syncRunRepository = lifecycle.runs();
        this.errorRepository = lifecycle.errors();
        this.clock = lifecycle.clock();
        this.syncMetrics = lifecycle.metrics();
    }

    public OrderSyncResult synchronize(OrderSyncPeriod period) {
        return synchronize(period, SyncExecutionContext.manual());
    }

    public OrderSyncResult synchronize(
            OrderSyncPeriod period,
            SyncExecutionContext context
    ) {
        return syncMetrics.record(
                SyncScope.ORDERS,
                context.triggerType(),
                () -> synchronizeInternal(period, context)
        );
    }

    public OrderSyncResult synchronizeWebhookOrder(String orderExternalId) {
        if (!StringUtils.hasText(orderExternalId)) {
            throw new IllegalArgumentException("orderExternalId is required");
        }
        String externalId = orderExternalId.trim();
        return syncMetrics.record(
                SyncScope.ORDERS,
                SyncTriggerType.REPROCESS,
                () -> synchronizeWebhookOrderInternal(externalId)
        );
    }

    private OrderSyncResult synchronizeWebhookOrderInternal(
            String orderExternalId
    ) {
        IntegrationConnection connection = activeLiveSkladConnection();
        LiveSkladOrderDetailPayload detail = liveSkladClient
                .fetchOrderDetail(orderExternalId);
        if (detail == null
                || !orderExternalId.equals(detail.externalId())
                || detail.sourceUpdatedAt() == null) {
            throw new LiveSkladOrderChangedException();
        }
        Store store = storeRepository.findByConnectionIdAndExternalId(
                connection.getId(),
                detail.storeExternalId()
        ).filter(Store::isActive).orElseThrow(() -> new IllegalStateException(
                "Active LiveSklad store for webhook order is not synchronized"
        ));
        Instant periodStart = detail.sourceUpdatedAt();
        SyncRun syncRun = syncRunRepository.save(SyncRun.startOrderSync(
                connection,
                new SyncPeriod(periodStart, periodStart.plusNanos(1)),
                SyncTriggerType.REPROCESS,
                null,
                null,
                clock.instant()
        ));
        try {
            OrderSyncBatchResult batch = persistence.synchronizeTargeted(
                    syncRun.getId(),
                    store,
                    LiveSkladOrderSource.fromDetail(detail)
            );
            syncRun.complete(
                    1,
                    batch.documentsCreated(),
                    batch.documentsUpdated(),
                    batch.documentsSkipped(),
                    clock.instant()
            );
            return OrderSyncResult.from(syncRunRepository.save(syncRun), batch);
        } catch (RuntimeException exception) {
            String summary = "Order webhook synchronization failed: "
                    + exception.getClass().getSimpleName();
            failSyncRun(
                    syncRun,
                    1,
                    summary,
                    exception instanceof LiveSkladException
            );
            LOGGER.warn(
                    "Order webhook synchronization run {} failed with {}",
                    syncRun.getId(),
                    exception.getClass().getSimpleName()
            );
            throw new OrderSyncException(syncRun.getId(), exception);
        }
    }

    private OrderSyncResult synchronizeInternal(
            OrderSyncPeriod period,
            SyncExecutionContext context
    ) {
        IntegrationConnection connection = activeLiveSkladConnection();
        List<Store> stores = storeRepository
                .findAllByConnectionIdAndActiveTrueOrderByExternalId(
                        connection.getId()
                );
        if (stores.isEmpty()) {
            throw new IllegalStateException(
                    "No active LiveSklad stores found; synchronize stores first"
            );
        }
        SyncRun syncRun = syncRunRepository.save(SyncRun.startOrderSync(
                connection,
                new SyncPeriod(period.start(), period.end()),
                context.triggerType(),
                context.syncJobId(),
                context.requestedBy(),
                clock.instant()
        ));
        int fetched = 0;
        try {
            Map<String, Store> storesByExternalId = new HashMap<>();
            for (Store store : stores) {
                storesByExternalId.put(store.getExternalId(), store);
            }
            List<LiveSkladOrderSummaryPayload> summaries = liveSkladClient
                    .fetchOrders(period.start(), period.end());
            Set<String> orderIds = new HashSet<>();
            List<LiveSkladOrderSummaryPayload> relevant = new ArrayList<>();
            for (LiveSkladOrderSummaryPayload summary : summaries) {
                if (!orderIds.add(summary.externalId())) {
                    throw new IllegalStateException(
                            "LiveSklad orders contain a duplicate company-wide ID"
                    );
                }
                if (storesByExternalId.containsKey(summary.storeExternalId())) {
                    relevant.add(summary);
                }
            }
            fetched = relevant.size();
            if (fetched > MAX_DETAILS_PER_RUN) {
                failSyncRun(
                        syncRun,
                        fetched,
                        "Order synchronization window exceeds safe detail limit",
                        false
                );
                throw new OrderSyncCapacityException(
                        syncRun.getId(),
                        fetched,
                        MAX_DETAILS_PER_RUN
                );
            }

            Map<Store, List<LiveSkladOrderSource>> byStore =
                    new LinkedHashMap<>();
            for (Store store : stores) {
                byStore.put(store, new ArrayList<>());
            }
            for (LiveSkladOrderSummaryPayload summary : relevant) {
                LiveSkladOrderDetailPayload detail = liveSkladClient
                        .fetchOrderDetail(summary.externalId());
                Store store = storesByExternalId.get(summary.storeExternalId());
                byStore.get(store).add(new LiveSkladOrderSource(summary, detail));
            }
            List<StoreOrderBatch> batches = byStore.entrySet().stream()
                    .map(entry -> new StoreOrderBatch(
                            entry.getKey(),
                            entry.getValue()
                    ))
                    .toList();
            OrderSyncBatchResult batch = persistence.synchronize(
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
            return OrderSyncResult.from(syncRunRepository.save(syncRun), batch);
        } catch (OrderSyncCapacityException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            String summary = "Order synchronization failed: "
                    + exception.getClass().getSimpleName();
            failSyncRun(
                    syncRun,
                    fetched,
                    summary,
                    exception instanceof LiveSkladException
            );
            LOGGER.warn(
                    "Order synchronization run {} failed with {}",
                    syncRun.getId(),
                    exception.getClass().getSimpleName()
            );
            throw new OrderSyncException(syncRun.getId(), exception);
        }
    }

    private IntegrationConnection activeLiveSkladConnection() {
        return connectionRepository
                .findByConnectionKeyAndActiveTrue(LIVESKLAD_CONNECTION_KEY)
                .filter(candidate ->
                        candidate.getSourceSystem() == SourceSystem.LIVESKLAD
                ).orElseThrow(() -> new IllegalStateException(
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
        errorRepository.save(SyncRunError.orderSyncFailure(
                failedRun,
                summary,
                retryable,
                now
        ));
    }
}
