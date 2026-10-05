package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeanalytics.interpretation.snapshot.WeeklySnapshotPlanningStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

class SellerWeeklyPreparationBatchServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");
    private final WeeklySnapshotPlanningStore stores = mock(WeeklySnapshotPlanningStore.class);
    private final SellerWeeklyPreparationStore queue = mock(SellerWeeklyPreparationStore.class);
    private final SellerWeeklyPreparationRunner runner = mock(SellerWeeklyPreparationRunner.class);

    @Test
    void disabledDoesNotReadStoresDiscoverRefreshOrClaim() {
        assertThat(service(false, () -> 0L).reconcile()).isEqualTo(
                new SellerWeeklyPreparationBatchService.Result(0, 0, 0, 0, 0, 0));
        verifyNoInteractions(stores, queue, runner);
    }

    @Test
    void boundedSweepResumesAfterPageAndRestartsAfterExhaustion() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(runner.prepareNext(anyString())).thenReturn(result("IDLE"));
        when(stores.activeStoresAfter(null, 2)).thenReturn(List.of(target(first), target(second)));
        when(stores.activeStoresAfter(second, 2)).thenReturn(List.of());
        when(queue.discover(any(), eq(NOW), eq(4))).thenReturn(discovery(4));
        var service = service(true, () -> 0L);

        assertThat(service.reconcile().discovered()).isEqualTo(8);
        assertThat(service.reconcile().scanned()).isZero();
        assertThat(service.reconcile().scanned()).isEqualTo(2);

        verify(stores, times(2)).activeStoresAfter(isNull(), eq(2));
        verify(stores).activeStoresAfter(second, 2);
        verify(queue, times(3)).requeueStaleSnapshots(3, NOW);
    }

    @Test
    void badDiscoveryDoesNotPinCursorOrHideAnotherStore() {
        UUID bad = UUID.randomUUID();
        UUID good = UUID.randomUUID();
        when(runner.prepareNext(anyString())).thenReturn(result("IDLE"));
        when(stores.activeStoresAfter(null, 2)).thenReturn(List.of(target(bad), target(good)));
        when(stores.activeStoresAfter(good, 2)).thenReturn(List.of());
        when(queue.discover(bad, NOW, 4)).thenThrow(new IllegalStateException("synthetic private message"));
        when(queue.discover(good, NOW, 4)).thenReturn(discovery(1));
        var service = service(true, () -> 0L);

        assertThat(service.reconcile()).isEqualTo(
                new SellerWeeklyPreparationBatchService.Result(2, 1, 0, 0, 0, 1));
        service.reconcile();
        verify(stores).activeStoresAfter(good, 2);
        verify(queue).discover(good, NOW, 4);
    }

    @Test
    void preparationWaitAndFailureConsumeFreeBatchWithoutBusyRetry() {
        when(runner.prepareNext(anyString())).thenReturn(result("WAITING_SOURCES"), result("FAILED"));
        when(stores.activeStoresAfter(null, 2)).thenReturn(List.of());
        var service = service(true, () -> 0L);

        assertThat(service.reconcile()).isEqualTo(
                new SellerWeeklyPreparationBatchService.Result(0, 0, 0, 0, 1, 1));
        verify(runner, times(2)).prepareNext(anyString());
    }

    @Test
    void timeBudgetStopsBetweenPreparationsBeforeDiscovery() {
        LongSupplier nano = mock(LongSupplier.class);
        when(nano.getAsLong()).thenReturn(0L, 0L, Duration.ofSeconds(1).toNanos());
        when(runner.prepareNext(anyString())).thenReturn(result("SUCCEEDED"));
        var service = service(true, nano);

        assertThat(service.reconcile().prepared()).isOne();
        verify(runner).prepareNext(anyString());
        verifyNoInteractions(stores, queue);
    }

    @Test
    void timeBudgetDuringStoreSweepKeepsLastCompletedCursor() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        LongSupplier nano = mock(LongSupplier.class);
        when(nano.getAsLong()).thenReturn(0L, 0L, 0L, 0L, 0L, Duration.ofSeconds(1).toNanos());
        when(runner.prepareNext(anyString())).thenReturn(result("IDLE"));
        when(stores.activeStoresAfter(null, 2)).thenReturn(List.of(target(first), target(second)));
        when(queue.discover(first, NOW, 4)).thenReturn(discovery(1));
        var service = service(true, nano);
        assertThat(service.reconcile().scanned()).isOne();
        verify(queue, never()).discover(eq(second), any(), anyInt());
        when(nano.getAsLong()).thenReturn(0L);
        when(stores.activeStoresAfter(first, 2)).thenReturn(List.of(target(second)));
        when(queue.discover(second, NOW, 4)).thenReturn(discovery(1));
        assertThat(service.reconcile().scanned()).isOne();
        verify(stores).activeStoresAfter(first, 2);
    }

    @Test
    void slowPreparationCannotPermanentlyStarveDiscovery() {
        LongSupplier nano = mock(LongSupplier.class);
        when(nano.getAsLong()).thenReturn(0L, 0L, Duration.ofSeconds(1).toNanos());
        when(runner.prepareNext(anyString())).thenReturn(result("SUCCEEDED"));
        var service = service(true, nano);
        service.reconcile();
        verifyNoInteractions(stores);
        when(nano.getAsLong()).thenReturn(0L, 0L, Duration.ofSeconds(1).toNanos());
        when(stores.activeStoresAfter(null, 2)).thenReturn(List.of());
        service.reconcile();
        verify(stores).activeStoresAfter(null, 2);
        verify(runner).prepareNext(anyString());
    }

    @Test
    void staleRefreshGetsFirstTurnEvenWhenOtherPhasesConsumeEveryBudget() {
        LongSupplier nano = mock(LongSupplier.class);
        when(nano.getAsLong()).thenReturn(0L, 0L, Duration.ofSeconds(1).toNanos());
        when(runner.prepareNext(anyString())).thenReturn(result("SUCCEEDED"));
        var service = service(true, nano);
        service.reconcile();
        when(nano.getAsLong()).thenReturn(0L, 0L, Duration.ofSeconds(1).toNanos());
        when(stores.activeStoresAfter(null, 2)).thenReturn(List.of());
        service.reconcile();
        verifyNoInteractions(queue);
        when(nano.getAsLong()).thenReturn(0L, 0L, Duration.ofSeconds(1).toNanos());
        when(queue.requeueStaleSnapshots(3, NOW)).thenReturn(2);
        assertThat(service.reconcile().reopened()).isEqualTo(2);
        verify(queue).requeueStaleSnapshots(3, NOW);
        verify(runner).prepareNext(anyString());
    }

    private SellerWeeklyPreparationBatchService service(boolean enabled, LongSupplier nano) {
        return new SellerWeeklyPreparationBatchService(stores, queue, runner,
                new SellerWeeklyPreparationProperties(enabled, Duration.ofMinutes(1), 2, 4, 3, 2,
                        Duration.ofSeconds(1)), Clock.fixed(NOW, ZoneOffset.UTC), nano);
    }

    private SellerWeeklyPreparationRunner.Result result(String state) {
        return new SellerWeeklyPreparationRunner.Result(null, state, null);
    }

    private WeeklySnapshotPlanningStore.StoreTarget target(UUID store) {
        return new WeeklySnapshotPlanningStore.StoreTarget(store, "UTC");
    }

    private SellerWeeklyPreparationStore.Discovery discovery(int count) {
        return new SellerWeeklyPreparationStore.Discovery(count, "MORE_PAGES", LocalDate.parse("2026-09-28"));
    }
}
