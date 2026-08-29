package com.storeanalytics.report.model;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.common.persistence.AbstractMutableEntity;
import com.storeanalytics.metrics.model.ReportSnapshot;
import com.storeanalytics.store.model.Store;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;

@Entity
@Table(name = "report_backfill_jobs")
public class ReportBackfillJob extends AbstractMutableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by")
    private AppUser requestedBy;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "report_year", nullable = false)
    private int year;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReportBackfillJobStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReportBackfillJobPhase phase;

    @Column(name = "cursor_month", nullable = false)
    private int cursorMonth;

    @Column(name = "paid_month_count", nullable = false)
    private int paidMonthCount;

    @Column(name = "monthly_created_count", nullable = false)
    private int monthlyCreatedCount;

    @Column(name = "monthly_existing_count", nullable = false)
    private int monthlyExistingCount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "annual_report_id")
    private ReportSnapshot annualReport;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "completed_steps", nullable = false)
    private int completedSteps;

    @Column(name = "total_retries", nullable = false)
    private int totalRetries;

    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "lease_owner", length = 100)
    private String leaseOwner;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "error_summary", length = 300)
    private String errorSummary;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected ReportBackfillJob() {
    }

    public static ReportBackfillJob create(
            ReportBackfillJobDefinition definition,
            Instant now
    ) {
        ReportBackfillJobDefinition source = requireNonNull(
                definition,
                "definition"
        );
        require(source.year() >= 2000 && source.year() <= 2100,
                "report backfill year must be between 2000 and 2100");
        require(source.maxAttempts() >= 1 && source.maxAttempts() <= 10,
                "maxAttempts must be between 1 and 10");
        ReportBackfillJob job = new ReportBackfillJob();
        job.store = requireNonNull(source.store(), "store");
        job.requestedBy = requireNonNull(source.requestedBy(), "requestedBy");
        job.idempotencyKey = requireText(
                source.idempotencyKey(),
                "idempotencyKey"
        );
        require(job.idempotencyKey.length() <= 100,
                "idempotencyKey must not exceed 100 characters");
        job.year = source.year();
        job.status = ReportBackfillJobStatus.PENDING;
        job.phase = ReportBackfillJobPhase.MONTHLY;
        job.cursorMonth = 1;
        job.maxAttempts = source.maxAttempts();
        job.nextAttemptAt = requireNonNull(now, "now");
        return job;
    }

    public void claim(String owner, Duration leaseDuration, Instant now) {
        require(status == ReportBackfillJobStatus.PENDING
                        || status == ReportBackfillJobStatus.WAITING_RETRY,
                "only a pending report backfill job can be claimed");
        require(!nextAttemptAt.isAfter(now), "report backfill job is not ready");
        require(!cancelRequested, "cancelled report backfill job cannot be claimed");
        requireNonNull(leaseDuration, "leaseDuration");
        require(!leaseDuration.isZero() && !leaseDuration.isNegative(),
                "leaseDuration must be positive");
        status = ReportBackfillJobStatus.RUNNING;
        leaseOwner = requireText(owner, "owner");
        leaseUntil = now.plus(leaseDuration);
        if (startedAt == null) {
            startedAt = now;
        }
    }

    public boolean cancelClaimedIfRequested(String owner, Instant now) {
        requireOwnedRunningJob(owner);
        if (!cancelRequested) {
            return false;
        }
        clearLease();
        cancel(now);
        return true;
    }

    public void completeMonthlyStep(
            String owner,
            boolean paidMonth,
            boolean created,
            Instant now
    ) {
        requireOwnedRunningJob(owner);
        require(phase == ReportBackfillJobPhase.MONTHLY,
                "report backfill job is not in monthly phase");
        require(!created || paidMonth, "created report requires a paid month");
        if (paidMonth) {
            paidMonthCount++;
            if (created) {
                monthlyCreatedCount++;
            } else {
                monthlyExistingCount++;
            }
        }
        completedSteps++;
        resetAttemptState();
        clearLease();
        if (cancelRequested) {
            cancel(now);
        } else {
            if (cursorMonth == 12) {
                phase = ReportBackfillJobPhase.ANNUAL;
            } else {
                cursorMonth++;
            }
            status = ReportBackfillJobStatus.PENDING;
            nextAttemptAt = now;
        }
    }

    public void completeAnnualStep(
            String owner,
            ReportSnapshot report,
            Instant now
    ) {
        requireOwnedRunningJob(owner);
        require(phase == ReportBackfillJobPhase.ANNUAL,
                "report backfill job is not in annual phase");
        annualReport = report;
        completedSteps++;
        resetAttemptState();
        clearLease();
        if (cancelRequested) {
            cancel(now);
        } else {
            status = ReportBackfillJobStatus.SUCCESS;
            finishedAt = terminalTimestamp(now);
        }
    }

    public void retryOrFail(
            String owner,
            String summary,
            boolean retryable,
            Instant nextAttempt,
            Instant now
    ) {
        requireOwnedRunningJob(owner);
        errorSummary = boundedSummary(summary);
        attemptCount++;
        totalRetries++;
        clearLease();
        if (cancelRequested) {
            cancel(now);
        } else if (retryable && attemptCount < maxAttempts) {
            status = ReportBackfillJobStatus.WAITING_RETRY;
            nextAttemptAt = requireNonNull(nextAttempt, "nextAttempt");
        } else {
            status = ReportBackfillJobStatus.FAILED;
            finishedAt = terminalTimestamp(now);
        }
    }

    public void recoverExpiredLease(Instant nextAttempt, Instant now) {
        require(status == ReportBackfillJobStatus.RUNNING,
                "only a running report backfill job can be recovered");
        require(leaseUntil != null && leaseUntil.isBefore(now),
                "report backfill lease is not expired");
        retryOrFail(
                leaseOwner,
                "Report backfill worker lease expired",
                true,
                nextAttempt,
                now
        );
    }

    public void requestCancellation(Instant now) {
        if (status.isTerminal()) {
            return;
        }
        cancelRequested = true;
        if (status != ReportBackfillJobStatus.RUNNING) {
            clearLease();
            cancel(now);
        }
    }

    private void requireOwnedRunningJob(String owner) {
        require(status == ReportBackfillJobStatus.RUNNING,
                "report backfill job must be running");
        require(requireText(owner, "owner").equals(leaseOwner),
                "report backfill lease belongs to another worker");
    }

    private void resetAttemptState() {
        attemptCount = 0;
        errorSummary = null;
    }

    private void clearLease() {
        leaseOwner = null;
        leaseUntil = null;
    }

    private Instant terminalTimestamp(Instant now) {
        Instant candidate = requireNonNull(now, "now");
        return startedAt != null && candidate.isBefore(startedAt)
                ? startedAt : candidate;
    }

    private void cancel(Instant now) {
        status = ReportBackfillJobStatus.CANCELLED;
        errorSummary = null;
        finishedAt = terminalTimestamp(now);
    }

    private String boundedSummary(String summary) {
        String value = requireText(summary, "summary");
        return value.length() <= 300 ? value : value.substring(0, 300);
    }

    public Store getStore() {
        return store;
    }

    public AppUser getRequestedBy() {
        return requestedBy;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public int getYear() {
        return year;
    }

    public ReportBackfillJobStatus getStatus() {
        return status;
    }

    public ReportBackfillJobPhase getPhase() {
        return phase;
    }

    public int getCursorMonth() {
        return cursorMonth;
    }

    public int getPaidMonthCount() {
        return paidMonthCount;
    }

    public int getMonthlyCreatedCount() {
        return monthlyCreatedCount;
    }

    public int getMonthlyExistingCount() {
        return monthlyExistingCount;
    }

    public ReportSnapshot getAnnualReport() {
        return annualReport;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public int getCompletedSteps() {
        return completedSteps;
    }

    public int getTotalRetries() {
        return totalRetries;
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getLeaseUntil() {
        return leaseUntil;
    }

    public String getErrorSummary() {
        return errorSummary;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }
}
