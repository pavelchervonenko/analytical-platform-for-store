package com.storeanalytics.sync.service;

import com.storeanalytics.common.config.SyncProperties;
import com.storeanalytics.sync.exception.SyncJobNotFoundException;
import com.storeanalytics.sync.model.SyncJob;
import com.storeanalytics.sync.model.SyncJobStatus;
import com.storeanalytics.sync.repository.SyncJobRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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

    public SyncJobCoordinator(
            SyncJobRepository jobRepository,
            SyncProperties properties,
            Clock clock
    ) {
        this.jobRepository = jobRepository;
        this.properties = properties;
        this.clock = clock;
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
    public SyncJobView cancel(UUID jobId) {
        SyncJob job = locked(jobId);
        job.requestCancellation(clock.instant());
        return SyncJobView.from(job);
    }

    private void recoverOneExpiredLease(Instant now) {
        List<SyncJob> expired = jobRepository.findExpiredLeases(
                SyncJobStatus.RUNNING,
                now,
                PageRequest.of(0, 1)
        );
        if (!expired.isEmpty()) {
            expired.getFirst().recoverExpiredLease(
                    now.plus(properties.retryInitialDelay()),
                    now
            );
        }
    }

    private SyncJob locked(UUID jobId) {
        return jobRepository.findByIdForUpdate(jobId)
                .orElseThrow(() -> new SyncJobNotFoundException(jobId));
    }
}
