package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SellerWeeklyReviewSnapshotPlannerTest {
    @Test
    void capResumesAndExhaustionRestartsFullSweep() {
        var batch = mock(SellerWeeklyV3BatchPlanningService.class);
        var planner = new SellerWeeklyReviewSnapshotPlanner(batch,
                new WeeklyReviewSnapshotPlannerProperties(true, Duration.ofMinutes(5), 100));
        UUID cursor = UUID.randomUUID();
        var cap = new SellerWeeklyV3BatchPlanningResult(1, 1, 0, 0, cursor,
                SellerWeeklyV3BatchPlanningResult.StopReason.STORE_LIMIT);
        var empty = new SellerWeeklyV3BatchPlanningResult(0, 0, 0, 0, cursor,
                SellerWeeklyV3BatchPlanningResult.StopReason.EXHAUSTED);
        when(batch.scan(isNull(), any())).thenReturn(cap);
        when(batch.scan(eq(cursor), any())).thenReturn(empty);
        planner.reconcile();
        planner.reconcile();
        planner.reconcile();
        verify(batch, org.mockito.Mockito.times(2)).scan(isNull(), any());
        verify(batch).scan(eq(cursor), any());
        var budgets = org.mockito.ArgumentCaptor.forClass(SellerWeeklyV3BatchPlanningService.Budget.class);
        verify(batch, org.mockito.Mockito.times(3)).scan(any(), budgets.capture());
        assertThat(budgets.getAllValues()).allSatisfy(budget -> {
            assertThat(budget.maxStores()).isEqualTo(100);
            assertThat(budget.pageSize()).isEqualTo(25);
            assertThat(budget.timeBudget()).isEqualTo(Duration.ofMinutes(1));
        });
    }

    @Test
    void failureRetainsPreviouslyProcessedCursorWithoutSkippingTargets() {
        var batch = mock(SellerWeeklyV3BatchPlanningService.class);
        var planner = new SellerWeeklyReviewSnapshotPlanner(batch,
                new WeeklyReviewSnapshotPlannerProperties(true, Duration.ofMinutes(5), 10));
        UUID cursor = UUID.randomUUID();
        when(batch.scan(isNull(), any())).thenReturn(new SellerWeeklyV3BatchPlanningResult(1, 0, 1, 0, cursor,
                SellerWeeklyV3BatchPlanningResult.StopReason.TIME_BUDGET));
        when(batch.scan(eq(cursor), any())).thenThrow(new IllegalStateException("synthetic failure"));
        planner.reconcile();
        planner.reconcile();
        planner.reconcile();
        verify(batch, org.mockito.Mockito.times(2)).scan(eq(cursor), any());
    }
}
