package com.storeanalytics.sync.service;

import com.storeanalytics.integration.livesklad.client.HistoricalSalesReadScope;
import com.storeanalytics.sync.model.SyncRun;
import com.storeanalytics.sync.model.SyncScope;
import com.storeanalytics.sync.model.SyncStatus;
import com.storeanalytics.sync.repository.SyncJobRepository;
import com.storeanalytics.sync.repository.SyncRunRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HistoricalSalesRefreshBatchApplier {
    private final SalesSyncPersistence persistence;
    private final SyncJobRepository jobs;
    private final SyncRunRepository runs;
    private final Clock clock;
    private final HistoricalSalesDependencyGuard dependencies;
    private final EntityManager entityManager;

    public HistoricalSalesRefreshBatchApplier(SalesSyncPersistence persistence, SyncJobRepository jobs,
                                            SyncRunRepository runs, Clock clock,
                                            HistoricalSalesDependencyGuard dependencies, EntityManager entityManager) {
        this.persistence = persistence;
        this.jobs = jobs;
        this.runs = runs;
        this.clock = clock;
        this.dependencies = dependencies;
        this.entityManager = entityManager;
    }

    @Transactional
    public SalesSyncBatchResult apply(UUID runId, SalesSyncPeriod period, List<StoreSalesBatch> batches) {
        HistoricalSalesReadScope.Context context = HistoricalSalesReadScope.current();
        HistoricalSalesDependencyGuard.Snapshot before = null;
        if (context != null) {
            var job = jobs.findByIdForUpdate(context.jobId()).orElseThrow();
            HistoricalSalesRefreshService.validateLease(job, context, clock.instant());
            SyncRun run = runs.findById(runId).orElseThrow();
            if (!context.jobId().equals(run.getSyncJobId()) || run.getStatus() != SyncStatus.RUNNING
                    || run.getScope() != SyncScope.SALES
                    || !job.getConnection().getId().equals(run.getConnection().getId())
                    || !context.start().equals(run.getPeriodStart()) || !context.end().equals(run.getPeriodEnd())
                    || !period.start().equals(context.start()) || !period.end().equals(context.end())) {
                throw new IllegalStateException("Historical SALE publication does not match its claimed run");
            }
            dependencies.lockConnection(job.getConnection().getId());
            before = dependencies.capture(job.getConnection().getId(), period, batches);
            HistoricalSalesRefreshService.validateLease(job, context, clock.instant());
        } else {
            dependencies.lockConnection(runs.findById(runId).orElseThrow().getConnection().getId());
        }
        // Inner @Transactional persistence joins this transaction: the job lock fences ALL fact writes.
        var result = persistence.synchronize(runId, period, batches);
        if (before != null) {
            entityManager.flush();
            dependencies.verify(before);
            HistoricalSalesRefreshService.validateLease(jobs.findByIdForUpdate(context.jobId()).orElseThrow(),
                    context, clock.instant());
        }
        return result;
    }
}
