package com.storeanalytics.sync.service;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.integration.livesklad.client.HistoricalSalesReadScope;
import com.storeanalytics.sync.model.SyncJobType;
import com.storeanalytics.sync.model.SyncTriggerType;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class SyncJobExecutionService {

    private final StoreSyncService storeSyncService;
    private final EmployeeSyncService employeeSyncService;
    private final SalesSyncService salesSyncService;
    private final ReturnSyncService returnSyncService;
    private final OrderSyncService orderSyncService;
    private final AppUserRepository userRepository;
    private final HistoricalSalesRefreshService historicalSales;

    public SyncJobExecutionService(
            StoreSyncService storeSyncService,
            EmployeeSyncService employeeSyncService,
            SalesSyncService salesSyncService,
            ReturnSyncService returnSyncService,
            OrderSyncService orderSyncService,
            AppUserRepository userRepository,
            HistoricalSalesRefreshService historicalSales
    ) {
        this.storeSyncService = storeSyncService;
        this.employeeSyncService = employeeSyncService;
        this.salesSyncService = salesSyncService;
        this.returnSyncService = returnSyncService;
        this.orderSyncService = orderSyncService;
        this.userRepository = userRepository;
        this.historicalSales = historicalSales;
    }

    public UUID execute(SyncJobClaim claim) {
        if (claim.jobType() == SyncJobType.HISTORICAL_SALES) {
            if (claim.phase() != com.storeanalytics.sync.model.SyncJobPhase.SALES) {
                throw new IllegalArgumentException("Historical refresh requires the SALE phase");
            }
            HistoricalSalesReadScope.Context scope = new HistoricalSalesReadScope.Context(
                    claim.jobId(), claim.leaseOwner(), claim.attemptCount(), claim.windowStart(), claim.windowEnd());
            try (var ignored = HistoricalSalesReadScope.open(scope, historicalSales.maxRequestsPerStep(),
                    () -> historicalSales.chargeRequest(scope))) {
                return executePhase(claim);
            }
        }
        return executePhase(claim);
    }

    private UUID executePhase(SyncJobClaim claim) {
        AppUser requestedBy = claim.requestedById() == null
                ? null : userRepository.findById(claim.requestedById()).orElse(null);
        SyncTriggerType trigger = switch (claim.jobType()) {
            case BACKFILL -> SyncTriggerType.INITIAL;
            case INCREMENTAL -> SyncTriggerType.SCHEDULED;
            case HISTORICAL_SALES -> SyncTriggerType.REPROCESS;
            default -> throw new IllegalStateException("Unsupported synchronization job type");
        };
        SyncExecutionContext context = new SyncExecutionContext(
                trigger,
                claim.jobId(),
                requestedBy,
                claim.attemptCount()
        );
        return switch (claim.phase()) {
            case STORES -> storeSyncService.synchronize(context).syncRunId();
            case EMPLOYEES -> employeeSyncService.synchronize(context).syncRunId();
            case SALES -> salesSyncService.synchronize(
                    new SalesSyncPeriod(claim.windowStart(), claim.windowEnd()),
                    context
            ).syncRunId();
            case RETURNS -> returnSyncService.synchronize(
                    new ReturnSyncPeriod(claim.windowStart(), claim.windowEnd()),
                    context
            ).syncRunId();
            case ORDERS -> orderSyncService.synchronize(
                    new OrderSyncPeriod(claim.windowStart(), claim.windowEnd()),
                    context
            ).syncRunId();
            default -> throw new IllegalStateException("Unsupported synchronization job phase");
        };
    }
}
