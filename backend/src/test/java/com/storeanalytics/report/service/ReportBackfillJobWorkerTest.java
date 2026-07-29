package com.storeanalytics.report.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeanalytics.common.config.ReportBackfillProperties;
import com.storeanalytics.report.model.ReportBackfillJobPhase;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

class ReportBackfillJobWorkerTest {

    private final ReportBackfillJobCoordinator coordinator = mock(
            ReportBackfillJobCoordinator.class
    );
    private final ReportBackfillJobExecutionService executionService = mock(
            ReportBackfillJobExecutionService.class
    );
    private final ReportBackfillMetrics metrics = mock(
            ReportBackfillMetrics.class
    );
    private final ReportBackfillJobClaim claim = new ReportBackfillJobClaim(
            UUID.randomUUID(),
            ReportBackfillJobPhase.MONTHLY,
            0
    );
    private ReportBackfillJobWorker worker;

    @BeforeEach
    void setUp() {
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(metrics).recordStep(any(), any());
        worker = new ReportBackfillJobWorker(
                coordinator,
                executionService,
                new ReportBackfillProperties(
                        3,
                        Duration.ofMinutes(30),
                        Duration.ofSeconds(30),
                        Duration.ofMinutes(15),
                        20
                ),
                metrics
        );
    }

    @Test
    void executesClaimedStep() {
        when(coordinator.claimNext(anyString())).thenReturn(Optional.of(claim));

        worker.processNextStep();

        verify(executionService).execute(eq(claim), anyString());
        verify(coordinator, never()).retryOrFail(
                any(), anyString(), anyString(), eq(false), any()
        );
    }

    @Test
    void retriesTransientDatabaseFailureWithoutLeakingMessage() {
        when(coordinator.claimNext(anyString())).thenReturn(Optional.of(claim));
        org.mockito.Mockito.doThrow(new QueryTimeoutException("sensitive SQL"))
                .when(executionService).execute(eq(claim), anyString());

        worker.processNextStep();

        verify(coordinator).retryOrFail(
                eq(claim.jobId()),
                anyString(),
                eq("Report backfill phase MONTHLY failed: QueryTimeoutException"),
                eq(true),
                eq(Duration.ofSeconds(30))
        );
    }

    @Test
    void deterministicFailureIsNotRetried() {
        when(coordinator.claimNext(anyString())).thenReturn(Optional.of(claim));
        org.mockito.Mockito.doThrow(new IllegalStateException("sensitive"))
                .when(executionService).execute(eq(claim), anyString());

        worker.processNextStep();

        verify(coordinator).retryOrFail(
                eq(claim.jobId()),
                anyString(),
                anyString(),
                eq(false),
                eq(Duration.ofSeconds(30))
        );
    }
}
