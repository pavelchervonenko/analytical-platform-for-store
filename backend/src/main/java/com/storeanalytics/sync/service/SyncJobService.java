package com.storeanalytics.sync.service;

import com.storeanalytics.audit.service.AuditAction;
import com.storeanalytics.audit.service.AuditEntityType;
import com.storeanalytics.audit.service.AuditLogService;
import com.storeanalytics.audit.service.AuditTarget;
import com.storeanalytics.common.exception.InvalidRequestException;
import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.common.config.SyncProperties;
import com.storeanalytics.integration.connection.model.IntegrationConnection;
import com.storeanalytics.integration.connection.repository.IntegrationConnectionRepository;
import com.storeanalytics.sync.exception.ActiveSyncJobException;
import com.storeanalytics.sync.exception.SyncJobNotFoundException;
import com.storeanalytics.sync.model.SourceSystem;
import com.storeanalytics.sync.model.SyncJob;
import com.storeanalytics.sync.model.SyncJobStatus;
import com.storeanalytics.sync.model.SyncJobType;
import com.storeanalytics.sync.repository.SyncJobRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SyncJobService {

    private static final String LIVESKLAD_CONNECTION_KEY = "livesklad-default";
    private static final Set<SyncJobStatus> ACTIVE_STATUSES = Set.of(
            SyncJobStatus.PENDING,
            SyncJobStatus.RUNNING,
            SyncJobStatus.WAITING_RETRY
    );

    private final SyncJobRepository jobRepository;
    private final IntegrationConnectionRepository connectionRepository;
    private final AppUserRepository userRepository;
    private final SyncProperties properties;
    private final Clock clock;
    private final AuditLogService auditLogService;

    public SyncJobService(
            SyncJobRepository jobRepository,
            IntegrationConnectionRepository connectionRepository,
            AppUserRepository userRepository,
            SyncProperties properties,
            Clock clock,
            AuditLogService auditLogService
    ) {
        this.jobRepository = jobRepository;
        this.connectionRepository = connectionRepository;
        this.userRepository = userRepository;
        this.properties = properties;
        this.clock = clock;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public SyncJobView createBackfill(
            LocalDate periodStart,
            LocalDate periodEndInclusive,
            UUID requestedById
    ) {
        validateBackfillPeriod(periodStart, periodEndInclusive);
        Instant start = periodStart.atStartOfDay(properties.reportingZone()).toInstant();
        Instant end = periodEndInclusive.plusDays(1)
                .atStartOfDay(properties.reportingZone())
                .toInstant();
        AppUser requestedBy = userRepository.findById(requestedById)
                .orElseThrow(() -> new IllegalStateException("Requesting user no longer exists"));
        SyncJobView created = create(
                activeConnection(), requestedBy, SyncJobType.BACKFILL, start, end
        );
        auditLogService.record(
                requestedById,
                null,
                AuditAction.MANUAL_SYNC_STARTED,
                new AuditTarget(AuditEntityType.SYNC_JOB, created.id()),
                null,
                null,
                jobSummary(created)
        );
        return created;
    }

    @Transactional
    public Optional<SyncJobView> createScheduledIncremental() {
        LocalDate today = LocalDate.now(clock.withZone(properties.reportingZone()));
        Instant end = today.atStartOfDay(properties.reportingZone()).toInstant();
        Instant start = today.minusDays(properties.incrementalOverlapDays())
                .atStartOfDay(properties.reportingZone())
                .toInstant();
        IntegrationConnection connection = activeConnection();
        if (jobRepository.existsByConnectionIdAndJobTypeAndPeriodStartAndPeriodEnd(
                connection.getId(),
                SyncJobType.INCREMENTAL,
                start,
                end
        ) || hasActiveJob(connection)) {
            return Optional.empty();
        }
        SyncJobView created = create(
                connection,
                null,
                SyncJobType.INCREMENTAL,
                start,
                end
        );
        auditLogService.recordSystem(
                null,
                AuditAction.SCHEDULED_SYNC_STARTED,
                new AuditTarget(AuditEntityType.SYNC_JOB, created.id()),
                null,
                null,
                jobSummary(created)
        );
        return Optional.of(created);
    }

    @Transactional(readOnly = true)
    public SyncJobView get(UUID jobId) {
        return jobRepository.findById(jobId)
                .map(SyncJobView::from)
                .orElseThrow(() -> new SyncJobNotFoundException(jobId));
    }

    @Transactional(readOnly = true)
    public List<SyncJobView> list(int limit) {
        if (limit < 1 || limit > 100) {
            throw new InvalidRequestException("limit must be between 1 and 100");
        }
        return jobRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, limit))
                .stream()
                .map(SyncJobView::from)
                .toList();
    }

    private Map<String, Object> jobSummary(SyncJobView job) {
        return Map.of(
                "jobType", job.jobType(),
                "status", job.status(),
                "phase", job.phase(),
                "periodStart", job.periodStart(),
                "periodEnd", job.periodEnd(),
                "windowSizeMinutes", job.windowSizeMinutes()
        );
    }

    private SyncJobView create(
            IntegrationConnection connection,
            AppUser requestedBy,
            SyncJobType type,
            Instant start,
            Instant end
    ) {
        if (hasActiveJob(connection)) {
            throw new ActiveSyncJobException();
        }
        try {
            SyncJob job = SyncJob.create(
                    new com.storeanalytics.sync.model.SyncJobDefinition(
                            connection,
                            requestedBy,
                            type,
                            start,
                            end,
                            properties.windowSize(),
                            properties.maxAttempts()
                    ),
                    clock.instant()
            );
            return SyncJobView.from(jobRepository.saveAndFlush(job));
        } catch (DataIntegrityViolationException exception) {
            throw new ActiveSyncJobException();
        }
    }

    private boolean hasActiveJob(IntegrationConnection connection) {
        return jobRepository.existsByConnectionIdAndStatusIn(
                connection.getId(),
                ACTIVE_STATUSES
        );
    }

    private IntegrationConnection activeConnection() {
        return connectionRepository
                .findByConnectionKeyAndActiveTrue(LIVESKLAD_CONNECTION_KEY)
                .filter(candidate -> candidate.getSourceSystem() == SourceSystem.LIVESKLAD)
                .orElseThrow(() -> new IllegalStateException(
                        "Active LiveSklad integration connection is not configured"
                ));
    }

    private void validateBackfillPeriod(LocalDate start, LocalDate endInclusive) {
        if (start == null || endInclusive == null || endInclusive.isBefore(start)) {
            throw new InvalidRequestException("backfill period is invalid");
        }
        long days = ChronoUnit.DAYS.between(start, endInclusive) + 1;
        if (days > properties.maximumBackfillDays()) {
            throw new InvalidRequestException(
                    "backfill period exceeds " + properties.maximumBackfillDays() + " days"
            );
        }
    }
}
