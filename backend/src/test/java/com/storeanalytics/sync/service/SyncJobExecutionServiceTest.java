package com.storeanalytics.sync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.sync.model.SyncJobPhase;
import com.storeanalytics.sync.model.SyncJobType;
import com.storeanalytics.sync.model.SyncTriggerType;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

class SyncJobExecutionServiceTest {

    @ParameterizedTest
    @EnumSource(SyncJobPhase.class)
    void executesFirstScheduledAttemptAndPreservesZeroFence(SyncJobPhase phase) {
        verifyExecution(phase, SyncJobType.INCREMENTAL, 0, SyncTriggerType.SCHEDULED);
    }

    @ParameterizedTest
    @EnumSource(SyncJobPhase.class)
    void executesFirstBackfillAttemptAndPreservesZeroFence(SyncJobPhase phase) {
        verifyExecution(phase, SyncJobType.BACKFILL, 0, SyncTriggerType.INITIAL);
    }

    @ParameterizedTest
    @EnumSource(SyncJobPhase.class)
    void preservesPersistedCounterOnRetry(SyncJobPhase phase) {
        verifyExecution(phase, SyncJobType.INCREMENTAL, 2, SyncTriggerType.SCHEDULED);
    }

    private void verifyExecution(
            SyncJobPhase phase, SyncJobType type, int attempt, SyncTriggerType trigger
    ) {
        StoreSyncService stores = mock(StoreSyncService.class);
        EmployeeSyncService employees = mock(EmployeeSyncService.class);
        SalesSyncService sales = mock(SalesSyncService.class);
        ReturnSyncService returns = mock(ReturnSyncService.class);
        OrderSyncService orders = mock(OrderSyncService.class);
        SyncJobExecutionService service = new SyncJobExecutionService(
                stores, employees, sales, returns, orders, mock(AppUserRepository.class),
                mock(HistoricalSalesRefreshService.class));
        UUID runId = UUID.randomUUID();
        StoreSyncResult storeResult = mock(StoreSyncResult.class);
        EmployeeSyncResult employeeResult = mock(EmployeeSyncResult.class);
        SalesSyncResult salesResult = mock(SalesSyncResult.class);
        ReturnSyncResult returnResult = mock(ReturnSyncResult.class);
        OrderSyncResult orderResult = mock(OrderSyncResult.class);
        when(storeResult.syncRunId()).thenReturn(runId);
        when(employeeResult.syncRunId()).thenReturn(runId);
        when(salesResult.syncRunId()).thenReturn(runId);
        when(returnResult.syncRunId()).thenReturn(runId);
        when(orderResult.syncRunId()).thenReturn(runId);
        when(stores.synchronize(any(SyncExecutionContext.class))).thenReturn(storeResult);
        when(employees.synchronize(any(SyncExecutionContext.class))).thenReturn(employeeResult);
        when(sales.synchronize(any(SalesSyncPeriod.class), any())).thenReturn(salesResult);
        when(returns.synchronize(any(ReturnSyncPeriod.class), any())).thenReturn(returnResult);
        when(orders.synchronize(any(OrderSyncPeriod.class), any())).thenReturn(orderResult);
        UUID jobId = UUID.randomUUID();
        Instant start = Instant.parse("2026-09-30T22:00:00Z");
        Instant end = Instant.parse("2026-10-01T22:00:00Z");
        SyncJobClaim claim = new SyncJobClaim(jobId, null, type, phase, start, end, attempt);

        assertThat(service.execute(claim)).isEqualTo(runId);

        ArgumentCaptor<SyncExecutionContext> context = ArgumentCaptor.forClass(SyncExecutionContext.class);
        switch (phase) {
            case STORES -> verify(stores).synchronize(context.capture());
            case EMPLOYEES -> verify(employees).synchronize(context.capture());
            case SALES -> verify(sales).synchronize(any(SalesSyncPeriod.class), context.capture());
            case RETURNS -> verify(returns).synchronize(any(ReturnSyncPeriod.class), context.capture());
            case ORDERS -> verify(orders).synchronize(any(OrderSyncPeriod.class), context.capture());
            default -> throw new AssertionError("Unexpected phase");
        }
        assertThat(context.getValue().syncJobId()).isEqualTo(jobId);
        assertThat(context.getValue().triggerType()).isEqualTo(trigger);
        assertThat(context.getValue().jobAttempt()).isEqualTo(attempt);
    }
}
