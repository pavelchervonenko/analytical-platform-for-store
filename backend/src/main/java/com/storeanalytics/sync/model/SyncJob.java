package com.storeanalytics.sync.model;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.common.persistence.AbstractMutableEntity;
import com.storeanalytics.integration.connection.model.IntegrationConnection;
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
@Table(name = "sync_jobs")
public class SyncJob extends AbstractMutableEntity {

    public static final String EXPIRED_LEASE_ERROR_SUMMARY =
            "Synchronization worker lease expired";

    private static final Duration MINIMUM_WINDOW = Duration.ofMinutes(15);
    private static final int MAXIMUM_ERROR_SUMMARY_LENGTH = 300;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "connection_id", nullable = false)
    private IntegrationConnection connection;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by")
    private AppUser requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false)
    private SyncJobType jobType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SyncJobStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SyncJobPhase phase;

    @Column(name = "period_start", nullable = false)
    private Instant periodStart;

    @Column(name = "period_end", nullable = false)
    private Instant periodEnd;

    @Column(name = "cursor_start", nullable = false)
    private Instant cursorStart;

    @Column(name = "current_window_end", nullable = false)
    private Instant currentWindowEnd;

    @Column(name = "window_size_minutes", nullable = false)
    private int windowSizeMinutes;

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

    @Column(name = "error_summary")
    private String errorSummary;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected SyncJob() {
    }

    public static SyncJob create(SyncJobDefinition definition, Instant now) {
        requireNonNull(definition, "definition");
        IntegrationConnection connection = requireNonNull(
                definition.connection(),
                "connection"
        );
        require(connection.getSourceSystem() == SourceSystem.LIVESKLAD,
                "sync job requires a LiveSklad connection");
        SyncJobType jobType = requireNonNull(definition.jobType(), "jobType");
        Instant periodStart = requireNonNull(definition.periodStart(), "periodStart");
        Instant periodEnd = requireNonNull(definition.periodEnd(), "periodEnd");
        require(periodEnd.isAfter(periodStart), "periodEnd must be after periodStart");
        Duration windowSize = requireNonNull(definition.windowSize(), "windowSize");
        require(!windowSize.minus(MINIMUM_WINDOW).isNegative(),
                "windowSize must be at least 15 minutes");
        require(windowSize.compareTo(Duration.ofDays(31)) <= 0,
                "windowSize must not exceed 31 days");
        require(definition.maxAttempts() >= 1 && definition.maxAttempts() <= 20,
                "maxAttempts must be between 1 and 20");

        SyncJob job = new SyncJob();
        job.connection = connection;
        job.requestedBy = definition.requestedBy();
        job.jobType = jobType;
        job.status = SyncJobStatus.PENDING;
        job.phase = SyncJobPhase.STORES;
        job.periodStart = periodStart;
        job.periodEnd = periodEnd;
        job.cursorStart = periodStart;
        job.windowSizeMinutes = Math.toIntExact(windowSize.toMinutes());
        job.currentWindowEnd = job.nextWindowEnd(periodStart);
        job.maxAttempts = definition.maxAttempts();
        job.nextAttemptAt = requireNonNull(now, "now");
        return job;
    }

    public void claim(String owner, Duration leaseDuration, Instant now) {
        require(status == SyncJobStatus.PENDING || status == SyncJobStatus.WAITING_RETRY,
                "only a pending sync job can be claimed");
        require(!nextAttemptAt.isAfter(now), "sync job is not ready yet");
        require(!cancelRequested, "cancelled sync job cannot be claimed");
        requireNonNull(leaseDuration, "leaseDuration");
        require(!leaseDuration.isZero() && !leaseDuration.isNegative(),
                "leaseDuration must be positive");
        status = SyncJobStatus.RUNNING;
        leaseOwner = requireText(owner, "owner");
        leaseUntil = requireNonNull(now, "now").plus(leaseDuration);
        if (startedAt == null) {
            startedAt = now;
        }
    }

    public void completeStep(String owner, Instant now) {
        requireOwnedRunningJob(owner);
        completedSteps++;
        attemptCount = 0;
        errorSummary = null;
        clearLease();
        if (cancelRequested) {
            cancel(now);
            return;
        }
        switch (phase) {
            case STORES -> phase = SyncJobPhase.EMPLOYEES;
            case EMPLOYEES -> phase = SyncJobPhase.SALES;
            case SALES -> phase = SyncJobPhase.RETURNS;
            case RETURNS -> advanceWindow(now);
            default -> throw new IllegalStateException("Unsupported sync job phase");
        }
        if (!status.isTerminal()) {
            status = SyncJobStatus.PENDING;
            nextAttemptAt = now;
        }
    }

    public boolean shrinkCurrentWindow(String owner, Instant now) {
        requireOwnedRunningJob(owner);
        Duration currentSize = Duration.between(cursorStart, currentWindowEnd);
        if (currentSize.compareTo(MINIMUM_WINDOW.multipliedBy(2)) < 0) {
            return false;
        }
        long halfMillis = currentSize.toMillis() / 2;
        currentWindowEnd = cursorStart.plusMillis(halfMillis);
        attemptCount = 0;
        errorSummary = null;
        clearLease();
        status = SyncJobStatus.PENDING;
        nextAttemptAt = now;
        return true;
    }

    public boolean shrinkCurrentWindowForRetry(
            String owner,
            String summary,
            Instant nextAttempt,
            Instant now
    ) {
        requireOwnedRunningJob(owner);
        Duration currentSize = Duration.between(cursorStart, currentWindowEnd);
        if (currentSize.compareTo(MINIMUM_WINDOW.multipliedBy(2)) < 0) {
            return false;
        }
        long halfMillis = currentSize.toMillis() / 2;
        currentWindowEnd = cursorStart.plusMillis(halfMillis);
        attemptCount = 0;
        totalRetries++;
        errorSummary = boundedSummary(summary);
        clearLease();
        if (cancelRequested) {
            cancel(now);
            return true;
        }
        status = SyncJobStatus.WAITING_RETRY;
        nextAttemptAt = requireNonNull(nextAttempt, "nextAttempt");
        return true;
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
            status = SyncJobStatus.WAITING_RETRY;
            nextAttemptAt = requireNonNull(nextAttempt, "nextAttempt");
        } else {
            status = SyncJobStatus.FAILED;
            finishedAt = requireNonNull(now, "now");
        }
    }

    public void recoverExpiredLease(Instant nextAttempt, Instant now) {
        require(status == SyncJobStatus.RUNNING, "only a running job can be recovered");
        require(leaseUntil != null && leaseUntil.isBefore(now), "job lease is not expired");
        String owner = leaseOwner;
        retryOrFail(
                owner,
                EXPIRED_LEASE_ERROR_SUMMARY,
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
        if (status != SyncJobStatus.RUNNING) {
            clearLease();
            cancel(now);
        }
    }

    private void advanceWindow(Instant now) {
        cursorStart = currentWindowEnd;
        if (!cursorStart.isBefore(periodEnd)) {
            currentWindowEnd = periodEnd;
            status = SyncJobStatus.SUCCESS;
            finishedAt = now;
            return;
        }
        currentWindowEnd = nextWindowEnd(cursorStart);
        phase = SyncJobPhase.SALES;
    }

    private Instant nextWindowEnd(Instant start) {
        Instant candidate = start.plus(Duration.ofMinutes(windowSizeMinutes));
        return candidate.isAfter(periodEnd) ? periodEnd : candidate;
    }

    private void cancel(Instant now) {
        status = SyncJobStatus.CANCELLED;
        finishedAt = now;
        errorSummary = null;
    }
    private String boundedSummary(String summary) {
        String value = requireText(summary, "summary");
        return value.length() <= MAXIMUM_ERROR_SUMMARY_LENGTH
                ? value : value.substring(0, MAXIMUM_ERROR_SUMMARY_LENGTH);
    }


    private void requireOwnedRunningJob(String owner) {
        require(status == SyncJobStatus.RUNNING, "sync job must be running");
        require(requireText(owner, "owner").equals(leaseOwner),
                "sync job lease belongs to another worker");
    }

    private void clearLease() {
        leaseOwner = null;
        leaseUntil = null;
    }

    public IntegrationConnection getConnection() {
        return connection;
    }

    public AppUser getRequestedBy() {
        return requestedBy;
    }

    public SyncJobType getJobType() {
        return jobType;
    }

    public SyncJobStatus getStatus() {
        return status;
    }

    public SyncJobPhase getPhase() {
        return phase;
    }

    public Instant getPeriodStart() {
        return periodStart;
    }

    public Instant getPeriodEnd() {
        return periodEnd;
    }

    public Instant getCursorStart() {
        return cursorStart;
    }

    public Instant getCurrentWindowEnd() {
        return currentWindowEnd;
    }

    public int getWindowSizeMinutes() {
        return windowSizeMinutes;
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
