package com.storeanalytics.report.service;

import com.storeanalytics.audit.service.AuditAction;
import com.storeanalytics.audit.service.AuditEntityType;
import com.storeanalytics.audit.service.AuditLogService;
import com.storeanalytics.audit.service.AuditTarget;
import com.storeanalytics.common.config.ReportBackfillProperties;
import com.storeanalytics.report.exception.ReportBackfillJobNotFoundException;
import com.storeanalytics.report.model.ReportBackfillJob;
import com.storeanalytics.report.repository.ReportBackfillJobRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportBackfillJobCoordinator {

    private final ReportBackfillJobRepository repository;
    private final ReportBackfillProperties properties;
    private final Clock clock;
    private final AuditLogService auditLogService;

    public ReportBackfillJobCoordinator(
            ReportBackfillJobRepository repository,
            ReportBackfillProperties properties,
            Clock clock,
            AuditLogService auditLogService
    ) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public Optional<ReportBackfillJobClaim> claimNext(String owner) {
        Instant now = clock.instant();
        recoverOneExpiredLease(now);
        return repository.findClaimable(now).map(job -> {
            job.claim(owner, properties.leaseDuration(), now);
            return new ReportBackfillJobClaim(
                    job.getId(),
                    job.getPhase(),
                    job.getAttemptCount()
            );
        });
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
    public ReportBackfillJobView cancel(UUID jobId, UUID actorId) {
        ReportBackfillJob job = locked(jobId);
        ReportBackfillJobView before = ReportBackfillJobView.from(job);
        job.requestCancellation(clock.instant());
        ReportBackfillJobView after = ReportBackfillJobView.from(job);
        if (before.status().isTerminal() || before.cancelRequested()) {
            return after;
        }
        auditLogService.record(
                actorId,
                job.getStore().getId(),
                AuditAction.REPORT_BACKFILL_CANCELLATION_REQUESTED,
                new AuditTarget(AuditEntityType.REPORT_BACKFILL, job.getId()),
                null,
                summary(before),
                summary(after)
        );
        return after;
    }

    private void recoverOneExpiredLease(Instant now) {
        repository.findExpiredLease(now).ifPresent(job -> job.recoverExpiredLease(
                now.plus(properties.retryInitialDelay()),
                now
        ));
    }

    private ReportBackfillJob locked(UUID jobId) {
        return repository.findByIdForUpdate(jobId)
                .orElseThrow(() -> new ReportBackfillJobNotFoundException(jobId));
    }

    private Map<String, Object> summary(ReportBackfillJobView job) {
        return Map.of(
                "status", job.status(),
                "phase", job.phase(),
                "cancelRequested", job.cancelRequested(),
                "completedSteps", job.completedSteps(),
                "totalRetries", job.totalRetries()
        );
    }
}
