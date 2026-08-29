package com.storeanalytics.report.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.store.model.Store;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReportBackfillJobTest {

    private static final String OWNER = "worker-1";
    private static final Instant START = Instant.parse("2026-07-26T10:00:00Z");

    @Test
    void advancesOneMonthAtATimeAndCompletesAnnualStep() {
        ReportBackfillJob job = newJob();

        for (int month = 1; month <= 12; month++) {
            Instant now = START.plusSeconds(month);
            job.claim(OWNER, Duration.ofMinutes(30), now);
            job.completeMonthlyStep(
                    OWNER,
                    month <= 2,
                    month == 1,
                    now
            );
        }

        assertThat(job.getPhase()).isEqualTo(ReportBackfillJobPhase.ANNUAL);
        assertThat(job.getStatus()).isEqualTo(ReportBackfillJobStatus.PENDING);
        assertThat(job.getCompletedSteps()).isEqualTo(12);
        assertThat(job.getPaidMonthCount()).isEqualTo(2);
        assertThat(job.getMonthlyCreatedCount()).isOne();
        assertThat(job.getMonthlyExistingCount()).isOne();

        Instant finished = START.plusSeconds(20);
        job.claim(OWNER, Duration.ofMinutes(30), finished);
        job.completeAnnualStep(OWNER, null, finished);

        assertThat(job.getStatus()).isEqualTo(ReportBackfillJobStatus.SUCCESS);
        assertThat(job.getCompletedSteps()).isEqualTo(13);
        assertThat(job.getFinishedAt()).isEqualTo(finished);
    }

    @Test
    void cancellationStopsClaimedJobBeforeExecutingAnotherStep() {
        ReportBackfillJob job = newJob();
        job.claim(OWNER, Duration.ofMinutes(30), START);
        job.requestCancellation(START.plusSeconds(1));

        assertThat(job.cancelClaimedIfRequested(
                OWNER,
                START.plusSeconds(2)
        )).isTrue();
        assertThat(job.getStatus()).isEqualTo(ReportBackfillJobStatus.CANCELLED);
        assertThat(job.getCompletedSteps()).isZero();
        assertThat(job.getLeaseUntil()).isNull();
    }

    @Test
    void transientFailureRetriesUntilPersistedAttemptLimit() {
        ReportBackfillJob job = newJob();
        for (int attempt = 0; attempt < 2; attempt++) {
            Instant now = START.plusSeconds(attempt * 10L);
            job.claim(OWNER, Duration.ofMinutes(30), now);
            job.retryOrFail(
                    OWNER,
                    "temporary",
                    true,
                    now.plusSeconds(5),
                    now
            );
            assertThat(job.getStatus())
                    .isEqualTo(ReportBackfillJobStatus.WAITING_RETRY);
        }
        Instant last = START.plusSeconds(20);
        job.claim(OWNER, Duration.ofMinutes(30), last);
        job.retryOrFail(OWNER, "temporary", true, last.plusSeconds(5), last);

        assertThat(job.getStatus()).isEqualTo(ReportBackfillJobStatus.FAILED);
        assertThat(job.getAttemptCount()).isEqualTo(3);
        assertThat(job.getTotalRetries()).isEqualTo(3);
    }

    @Test
    void clampsSuccessfulTimestampWhenWallClockMovesBackwards() {
        ReportBackfillJob job = newJob();
        Instant firstClaim = START.plusSeconds(10);
        for (int month = 1; month <= 12; month++) {
            Instant now = firstClaim.plusSeconds(month);
            job.claim(OWNER, Duration.ofMinutes(30), now);
            job.completeMonthlyStep(OWNER, false, false, now);
        }
        job.claim(OWNER, Duration.ofMinutes(30), firstClaim.plusSeconds(20));
        job.completeAnnualStep(OWNER, null, START);

        assertThat(job.getStatus()).isEqualTo(ReportBackfillJobStatus.SUCCESS);
        assertThat(job.getFinishedAt()).isEqualTo(firstClaim.plusSeconds(1));
    }

    @Test
    void clampsFailedTimestampWhenWallClockMovesBackwards() {
        ReportBackfillJob job = newJob();
        Instant started = START.plusSeconds(10);
        job.claim(OWNER, Duration.ofMinutes(30), started);
        job.retryOrFail(OWNER, "permanent", false, START, START);

        assertThat(job.getStatus()).isEqualTo(ReportBackfillJobStatus.FAILED);
        assertThat(job.getFinishedAt()).isEqualTo(started);
    }

    @Test
    void clampsCancelledTimestampWhenWallClockMovesBackwards() {
        ReportBackfillJob job = newJob();
        Instant started = START.plusSeconds(10);
        job.claim(OWNER, Duration.ofMinutes(30), started);
        job.requestCancellation(START);

        assertThat(job.cancelClaimedIfRequested(OWNER, START)).isTrue();
        assertThat(job.getFinishedAt()).isEqualTo(started);
    }

    private ReportBackfillJob newJob() {
        return ReportBackfillJob.create(
                new ReportBackfillJobDefinition(
                        mock(Store.class),
                        mock(AppUser.class),
                        "request-12345678",
                        2025,
                        3
                ),
                START
        );
    }
}
