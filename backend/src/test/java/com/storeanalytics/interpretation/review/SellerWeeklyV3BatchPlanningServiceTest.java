package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.storeanalytics.interpretation.review.SellerWeeklyV3BatchPlanningResult.StopReason;
import com.storeanalytics.interpretation.review.SellerWeeklyV3BatchPlanningService.Budget;
import com.storeanalytics.interpretation.snapshot.WeeklySnapshotPlanningStore;
import com.storeanalytics.interpretation.snapshot.WeeklySnapshotPlanningStore.StoreTarget;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class SellerWeeklyV3BatchPlanningServiceTest {

    private final WeeklySnapshotPlanningStore stores = mock(WeeklySnapshotPlanningStore.class);
    private final SellerWeeklyV3PlanningService planner = mock(SellerWeeklyV3PlanningService.class);
    private final AtomicLong nanos = new AtomicLong();
    private final SellerWeeklyV3BatchPlanningService service =
            new SellerWeeklyV3BatchPlanningService(stores, planner, nanos::get);
    private final StoreTarget first = target();
    private final StoreTarget second = target();
    private final StoreTarget third = target();
    private final Budget budget = new Budget(3, 2, Duration.ofSeconds(1));

    @Test
    void capsTheLastPageAndKeepsOnlyCountersAndContinuationCursor() {
        when(stores.activeStoresAfter(null, 2)).thenReturn(List.of(first, second));
        when(stores.activeStoresAfter(second.storeId(), 1)).thenReturn(List.of(third));
        outcome(first, SellerWeeklyV3PlanningResult.Outcome.EVALUATED);
        outcome(second, SellerWeeklyV3PlanningResult.Outcome.UNCHANGED);
        outcome(third, SellerWeeklyV3PlanningResult.Outcome.DEFERRED);

        var result = service.scan(null, budget);

        assertThat(result).isEqualTo(new SellerWeeklyV3BatchPlanningResult(
                3, 1, 1, 1, third.storeId(), StopReason.STORE_LIMIT));
        var ordered = inOrder(stores, planner);
        ordered.verify(stores).activeStoresAfter(null, 2);
        ordered.verify(planner).evaluate(first.storeId());
        ordered.verify(planner).evaluate(second.storeId());
        ordered.verify(stores).activeStoresAfter(second.storeId(), 1);
        ordered.verify(planner).evaluate(third.storeId());
        ordered.verifyNoMoreInteractions();
    }

    @Test
    void resumesAnEmptyBatchWithoutMovingItsCursor() {
        when(stores.activeStoresAfter(first.storeId(), 2)).thenReturn(List.of());
        assertThat(service.scan(first.storeId(), budget)).isEqualTo(new SellerWeeklyV3BatchPlanningResult(
                0, 0, 0, 0, first.storeId(), StopReason.EXHAUSTED));
        verifyNoInteractions(planner);
    }

    @Test
    void stopsMidPageAfterTheLastCompletedStoreWithoutSkippingFetchedTargets() {
        when(stores.activeStoresAfter(null, 2)).thenReturn(List.of(first, second));
        when(planner.evaluate(first.storeId())).thenAnswer(invocation -> {
            nanos.set(Duration.ofSeconds(1).toNanos());
            return result(SellerWeeklyV3PlanningResult.Outcome.EVALUATED);
        });

        assertThat(service.scan(null, budget)).isEqualTo(new SellerWeeklyV3BatchPlanningResult(
                1, 1, 0, 0, first.storeId(), StopReason.TIME_BUDGET));
        verify(planner).evaluate(first.storeId());
        verifyNoMoreInteractions(planner);
    }

    @Test
    void checksBudgetAfterSlowTargetQueryBeforeStartingAnyStore() {
        when(stores.activeStoresAfter(null, 2)).thenAnswer(invocation -> {
            nanos.set(Duration.ofSeconds(1).toNanos());
            return List.of(first);
        });
        assertThat(service.scan(null, budget)).isEqualTo(new SellerWeeklyV3BatchPlanningResult(
                0, 0, 0, 0, null, StopReason.TIME_BUDGET));
        verifyNoInteractions(planner);
    }

    @Test
    void shortPagesContinueUsingTheLastProcessedCursor() {
        when(stores.activeStoresAfter(null, 2)).thenReturn(List.of(first));
        when(stores.activeStoresAfter(first.storeId(), 2)).thenReturn(List.of(second));
        when(stores.activeStoresAfter(second.storeId(), 1)).thenReturn(List.of());
        outcome(first, SellerWeeklyV3PlanningResult.Outcome.UNCHANGED);
        outcome(second, SellerWeeklyV3PlanningResult.Outcome.DEFERRED);
        assertThat(service.scan(null, budget)).isEqualTo(new SellerWeeklyV3BatchPlanningResult(
                2, 0, 1, 1, second.storeId(), StopReason.EXHAUSTED));
    }

    @Test
    void monotonicElapsedBudgetWorksAcrossNanoTimeWraparound() {
        nanos.set(Long.MAX_VALUE - 10);
        when(stores.activeStoresAfter(null, 2)).thenReturn(List.of(first, second));
        when(planner.evaluate(first.storeId())).thenAnswer(invocation -> {
            nanos.addAndGet(Duration.ofSeconds(1).toNanos());
            return result(SellerWeeklyV3PlanningResult.Outcome.DEFERRED);
        });
        assertThat(service.scan(null, budget).stopReason()).isEqualTo(StopReason.TIME_BUDGET);
        verify(planner).evaluate(first.storeId());
        verifyNoMoreInteractions(planner);
    }

    @Test
    void infrastructureFailureStopsTheBatchAndIsNotReportedAsDeferred() {
        when(stores.activeStoresAfter(null, 2)).thenReturn(List.of(first, second));
        var failure = new DataAccessResourceFailureException("Synthetic database unavailable");
        when(planner.evaluate(first.storeId())).thenThrow(failure);
        assertThatThrownBy(() -> service.scan(null, budget)).isSameAs(failure);
        verify(planner).evaluate(first.storeId());
        verifyNoMoreInteractions(planner);
    }

    @Test
    void targetQueryFailurePropagatesWithoutStartingAStore() {
        var failure = new DataAccessResourceFailureException("Synthetic target query unavailable");
        when(stores.activeStoresAfter(null, 2)).thenThrow(failure);
        assertThatThrownBy(() -> service.scan(null, budget)).isSameAs(failure);
        verifyNoInteractions(planner);
    }

    @Test
    void invalidBudgetsAreRejectedBeforeAnyDatabaseWork() {
        for (int limit : List.of(0, 101)) {
            assertThatThrownBy(() -> new Budget(limit, 2, Duration.ofSeconds(1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (int page : List.of(0, 26)) {
            assertThatThrownBy(() -> new Budget(3, page, Duration.ofSeconds(1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (Duration duration : List.of(Duration.ZERO, Duration.ofNanos(1), Duration.ofMinutes(6))) {
            assertThatThrownBy(() -> new Budget(3, 2, duration)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> service.scan(null, null)).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(stores, planner);
    }

    @Test
    void batchRejectsAnOuterTransactionAndCannotRetainSnapshotGraphs() throws NoSuchMethodException {
        var boundary = SellerWeeklyV3BatchPlanningService.class.getDeclaredMethod("scan", UUID.class, Budget.class)
                .getAnnotation(Transactional.class);
        assertThat(boundary.propagation()).isEqualTo(Propagation.NEVER);
        assertThat(SellerWeeklyV3BatchPlanningResult.class.getRecordComponents())
                .allSatisfy(component -> assertThat(component.getType()).isIn(
                        int.class, UUID.class, StopReason.class));
        assertThatThrownBy(() -> new SellerWeeklyV3BatchPlanningResult(
                1, 0, 0, 0, first.storeId(), StopReason.STORE_LIMIT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void outcome(StoreTarget store, SellerWeeklyV3PlanningResult.Outcome outcome) {
        when(planner.evaluate(store.storeId())).thenReturn(result(outcome));
    }

    private SellerWeeklyV3PlanningResult result(SellerWeeklyV3PlanningResult.Outcome outcome) {
        var snapshot = mock(PersistedWeeklyReviewV3Snapshot.class);
        var state = outcome == SellerWeeklyV3PlanningResult.Outcome.UNCHANGED
                ? SellerWeeklyV3ReadResult.State.CURRENT : SellerWeeklyV3ReadResult.State.STALE;
        return new SellerWeeklyV3PlanningResult(outcome, new SellerWeeklyV3ReadResult(state, Optional.of(snapshot)));
    }

    private StoreTarget target() {
        return new StoreTarget(UUID.randomUUID(), "Europe/Kaliningrad");
    }
}
