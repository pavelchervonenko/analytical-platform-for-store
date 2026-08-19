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
import com.storeanalytics.sync.exception.SyncClassificationRequiredException;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SyncJobService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SyncJobService.class);
    private static final String LIVESKLAD_CONNECTION_KEY = "livesklad-default";
    private static final Set<SyncJobStatus> ACTIVE_STATUSES = Set.of(
            SyncJobStatus.PENDING,
            SyncJobStatus.RUNNING,
            SyncJobStatus.WAITING_RETRY
    );
    private static final Set<String> RECOVERABLE_SCHEDULED_FAILURES = Set.of(
            "LIVESKLAD_ORDER_CHANGED",
            "LIVESKLAD_RETURN_CHANGED",
            "LIVESKLAD_RATE_LIMIT",
            "LIVESKLAD_TRANSPORT",
            "LIVESKLAD_HTTP_500",
            "LIVESKLAD_HTTP_502",
            "LIVESKLAD_HTTP_503",
            "LIVESKLAD_HTTP_504",
            "TRANSIENT_DATABASE"
    );

    private final SyncJobRepository jobRepository;
    private final IntegrationConnectionRepository connectionRepository;
    private final SyncClassificationReadinessService classificationReadinessService;
    private final AppUserRepository userRepository;
    private final SyncProperties properties;
    private final Clock clock;
    private final AuditLogService auditLogService;

    public SyncJobService(
            SyncJobRepository jobRepository,
            IntegrationConnectionRepository connectionRepository,
            SyncClassificationReadinessService classificationReadinessService,
            AppUserRepository userRepository,
            SyncProperties properties,
            Clock clock,
            AuditLogService auditLogService
    ) {
        this.jobRepository = jobRepository;
        this.connectionRepository = connectionRepository;
        this.classificationReadinessService = classificationReadinessService;
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
        IntegrationConnection connection = activeConnection();
        if (!classificationReadiness(connection, start).ready()) {
            throw new SyncClassificationRequiredException(periodStart);
        }
        AppUser requestedBy = userRepository.findById(requestedById)
                .orElseThrow(() -> new IllegalStateException("Requesting user no longer exists"));
        SyncJobView created = create(
                connection, requestedBy, SyncJobType.BACKFILL, start, end
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
        if (!classificationReadiness(connection, start).ready()) {
            LOGGER.warn(
                    "Skipped scheduled synchronization because no approved product "
                            + "classification is effective at {} for connection {}",
                    start,
                    connection.getConnectionKey()
            );
            return Optional.empty();
        }
        if (hasActiveJob(connection)) {
            LOGGER.info(
                    "Deferred scheduled synchronization for connection {} because another "
                            + "synchronization job is active",
                    connection.getConnectionKey()
            );
            return Optional.empty();
        }
        Optional<SyncJob> previous = jobRepository
                .findFirstByConnectionIdAndJobTypeAndPeriodStartAndPeriodEndOrderByCreatedAtDesc(
                        connection.getId(),
                        SyncJobType.INCREMENTAL,
                        start,
                        end
                );
        if (previous.isPresent() && !isRecoverableScheduledFailure(previous.get())) {
            return Optional.empty();
        }
        previous.ifPresent(job -> LOGGER.warn(
                "Retrying scheduled synchronization {} for connection {} after {}",
                job.getId(),
                connection.getConnectionKey(),
                job.getErrorSummary()
        ));
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

    private boolean isRecoverableScheduledFailure(SyncJob job) {
        if (job.getStatus() != SyncJobStatus.FAILED || job.getErrorSummary() == null) {
            return false;
        }
        return RECOVERABLE_SCHEDULED_FAILURES.stream()
                .anyMatch(job.getErrorSummary()::contains);
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

    @Transactional(readOnly = true)
    public SyncClassificationReadinessView classificationReadiness(
            LocalDate periodStart
    ) {
        if (periodStart == null) {
            throw new InvalidRequestException("periodStart is required");
        }
        Instant start = periodStart.atStartOfDay(
                properties.reportingZone()
        ).toInstant();
        return classificationReadiness(activeConnection(), start);
    }

    private SyncClassificationReadinessView classificationReadiness(
            IntegrationConnection connection,
            Instant periodStart
    ) {
        return classificationReadinessService.inspect(connection, periodStart);
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
