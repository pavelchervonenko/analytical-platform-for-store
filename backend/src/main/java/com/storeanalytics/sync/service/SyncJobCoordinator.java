package com.storeanalytics.sync.service;

import com.storeanalytics.audit.service.AuditAction;
import com.storeanalytics.audit.service.AuditEntityType;
import com.storeanalytics.audit.service.AuditLogService;
import com.storeanalytics.audit.service.AuditTarget;
import com.storeanalytics.common.config.SyncProperties;
import com.storeanalytics.sync.exception.SyncJobNotFoundException;
import com.storeanalytics.sync.model.SyncJob;
import com.storeanalytics.sync.model.SyncJobStatus;
import com.storeanalytics.sync.model.SyncRun;
import com.storeanalytics.sync.model.SyncRunError;
import com.storeanalytics.sync.model.SyncStatus;
import com.storeanalytics.sync.repository.SyncJobRepository;
import com.storeanalytics.sync.repository.SyncRunErrorRepository;
import com.storeanalytics.sync.repository.SyncRunRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SyncJobCoordinator {

    private static final Set<SyncJobStatus> CLAIMABLE_STATUSES = Set.of(
            SyncJobStatus.PENDING,
            SyncJobStatus.WAITING_RETRY
    );

    private final SyncJobRepository jobRepository;
    private final SyncProperties properties;
    private final Clock clock;
    private final AuditLogService auditLogService;
    private final SyncRunRepository syncRunRepository;
    private final SyncRunErrorRepository syncRunErrorRepository;

    public SyncJobCoordinator(
            SyncJobRepository jobRepository,
            SyncProperties properties,
            Clock clock,
            AuditLogService auditLogService,
            SyncRunRepository syncRunRepository,
            SyncRunErrorRepository syncRunErrorRepository
    ) {
        this.jobRepository = jobRepository;
        this.properties = properties;
        this.clock = clock;
        this.auditLogService = auditLogService;
        this.syncRunRepository = syncRunRepository;
        this.syncRunErrorRepository = syncRunErrorRepository;
    }

    @Transactional
    public Optional<SyncJobClaim> claimNext(String owner) {
        Instant now = clock.instant();
        recoverOneExpiredLease(now);
        List<SyncJob> candidates = jobRepository.findClaimable(
                CLAIMABLE_STATUSES,
                now,
                PageRequest.of(0, 1)
        );
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        SyncJob job = candidates.getFirst();
        job.claim(owner, properties.leaseDuration(), now);
        UUID requestedById = job.getRequestedBy() == null
                ? null : job.getRequestedBy().getId();
        return Optional.of(new SyncJobClaim(
                job.getId(),
                requestedById,
                job.getJobType(),
                job.getPhase(),
                job.getCursorStart(),
                job.getCurrentWindowEnd(),
                job.getAttemptCount()
        ));
    }

    @Transactional
    public void completeStep(UUID jobId, String owner) {
        locked(jobId).completeStep(owner, clock.instant());
    }

    @Transactional
    public boolean shrinkWindow(UUID jobId, String owner) {
        return locked(jobId).shrinkCurrentWindow(owner, clock.instant());
    }

    @Transactional
    public boolean shrinkWindowForRetry(
            UUID jobId,
            String owner,
            String summary,
            Duration delay
    ) {
        Instant now = clock.instant();
        return locked(jobId).shrinkCurrentWindowForRetry(
                owner,
                summary,
                now.plus(delay),
                now
        );
    }

    @Transactional
    public void retryOrFail(
            UUID jobId,
            String owner,
            String summary,
            boolean retryable,
            Duration delay
    ) {
        Instant now = clock.instant();
        locked(jobId).retryOrFail(
                owner,
                summary,
                retryable,
                now.plus(delay),
                now
        );
    }

    @Transactional
    public SyncJobView cancel(UUID jobId, UUID actorId) {
        SyncJob job = locked(jobId);
        SyncJobView before = SyncJobView.from(job);
        job.requestCancellation(clock.instant());
        SyncJobView after = SyncJobView.from(job);
        auditLogService.record(
                actorId,
                null,
                AuditAction.SYNC_JOB_CANCELLATION_REQUESTED,
                new AuditTarget(AuditEntityType.SYNC_JOB, job.getId()),
                null,
                cancellationSummary(before),
                cancellationSummary(after)
        );
        return after;
    }

    private Map<String, Object> cancellationSummary(SyncJobView job) {
        return Map.of(
                "status", job.status(),
                "phase", job.phase(),
                "cancelRequested", job.cancelRequested(),
                "completedSteps", job.completedSteps(),
                "totalRetries", job.totalRetries()
        );
    }

    private void recoverOneExpiredLease(Instant now) {
        List<SyncJob> expired = jobRepository.findExpiredLeases(
                SyncJobStatus.RUNNING,
                now,
                PageRequest.of(0, 1)
        );
        if (!expired.isEmpty()) {
            SyncJob job = expired.getFirst();
            closeInterruptedRuns(job.getId(), now);
            job.recoverExpiredLease(
                    now.plus(properties.retryInitialDelay()),
                    now
            );
        }
    }

    private void closeInterruptedRuns(UUID jobId, Instant now) {
        List<SyncRun> interruptedRuns = syncRunRepository
                .findAllByJobIdAndStatusForUpdate(jobId, SyncStatus.RUNNING);
        for (SyncRun run : interruptedRuns) {
            run.fail(
                    run.getRecordsFetched(),
                    SyncJob.EXPIRED_LEASE_ERROR_SUMMARY,
                    now
            );
            syncRunErrorRepository.save(SyncRunError.workerLeaseExpired(
                    run,
                    SyncJob.EXPIRED_LEASE_ERROR_SUMMARY,
                    now
            ));
        }
    }

    private SyncJob locked(UUID jobId) {
        return jobRepository.findByIdForUpdate(jobId)
                .orElseThrow(() -> new SyncJobNotFoundException(jobId));
    }
}
