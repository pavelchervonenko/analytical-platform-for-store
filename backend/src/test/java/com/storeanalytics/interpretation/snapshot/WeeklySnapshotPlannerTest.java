package com.storeanalytics.interpretation.snapshot;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class WeeklySnapshotPlannerTest {

    private final WeeklySnapshotPlanningService service = mock(
            WeeklySnapshotPlanningService.class
    );
    private final WeeklySnapshotPlanner planner = new WeeklySnapshotPlanner(service);

    @Test
    void delegatesIterationAndKeepsSchedulerAliveOnFailure() {
        planner.reconcile();
        verify(service).plan();

        doThrow(new IllegalStateException("database unavailable")).when(service).plan();
        assertThatCode(planner::reconcile).doesNotThrowAnyException();
    }
}
