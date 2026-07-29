package com.storeanalytics.report.service;

import com.storeanalytics.audit.service.AuditAction;
import com.storeanalytics.audit.service.AuditEntityType;
import com.storeanalytics.audit.service.AuditLogService;
import com.storeanalytics.audit.service.AuditTarget;
import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.common.config.ReportBackfillProperties;
import com.storeanalytics.common.exception.InvalidRequestException;
import com.storeanalytics.metrics.exception.StoreNotFoundException;
import com.storeanalytics.report.exception.ActiveReportBackfillJobException;
import com.storeanalytics.report.exception.ReportBackfillIdempotencyConflictException;
import com.storeanalytics.report.exception.ReportBackfillJobNotFoundException;
import com.storeanalytics.report.model.ReportBackfillJob;
import com.storeanalytics.report.model.ReportBackfillJobDefinition;
import com.storeanalytics.report.model.ReportBackfillJobStatus;
import com.storeanalytics.report.repository.ReportBackfillJobRepository;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.repository.StoreRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportBackfillJobService {

    private static final String CREATION_LOCK_SQL =
            "SELECT pg_advisory_xact_lock(1937006964, 20260726)";
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._:-]{7,99}"
    );
    private static final Set<ReportBackfillJobStatus> ACTIVE_STATUSES = Set.of(
            ReportBackfillJobStatus.PENDING,
            ReportBackfillJobStatus.RUNNING,
            ReportBackfillJobStatus.WAITING_RETRY
    );

    private final ReportBackfillJobRepository repository;
    private final StoreRepository storeRepository;
    private final AppUserRepository userRepository;
    private final ReportBackfillProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final AuditLogService auditLogService;

    public ReportBackfillJobService(
            ReportBackfillJobRepository repository,
            StoreRepository storeRepository,
            AppUserRepository userRepository,
            ReportBackfillProperties properties,
            JdbcTemplate jdbcTemplate,
            Clock clock,
            AuditLogService auditLogService
    ) {
        this.repository = repository;
        this.storeRepository = storeRepository;
        this.userRepository = userRepository;
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public ReportBackfillJobView create(
            UUID storeId,
            int year,
            UUID requestedById,
            String idempotencyKey
    ) {
        validateYear(year);
        String key = validateIdempotencyKey(idempotencyKey);
        Optional<ReportBackfillJob> existing = repository
                .findByRequestedByIdAndIdempotencyKey(requestedById, key);
        if (existing.isPresent()) {
            return matching(existing.get(), storeId, year);
        }
        jdbcTemplate.execute(CREATION_LOCK_SQL);
        existing = repository.findByRequestedByIdAndIdempotencyKey(
                requestedById,
                key
        );
        if (existing.isPresent()) {
            return matching(existing.get(), storeId, year);
        }
        if (repository.existsByStoreIdAndStatusIn(storeId, ACTIVE_STATUSES)) {
            throw new ActiveReportBackfillJobException(
                    "An active report backfill job exists for the store"
            );
        }
        if (repository.countByStatusIn(ACTIVE_STATUSES)
                >= properties.maxActiveJobs()) {
            throw new ActiveReportBackfillJobException(
                    "Global report backfill job capacity is exhausted"
            );
        }
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new StoreNotFoundException(storeId));
        AppUser requestedBy = userRepository.findById(requestedById)
                .orElseThrow(() -> new IllegalStateException(
                        "Requesting user no longer exists"
                ));
        ReportBackfillJob job = repository.saveAndFlush(ReportBackfillJob.create(
                new ReportBackfillJobDefinition(
                        store,
                        requestedBy,
                        key,
                        year,
                        properties.maxAttempts()
                ),
                clock.instant()
        ));
        ReportBackfillJobView result = ReportBackfillJobView.from(job);
        auditLogService.record(
                requestedById,
                storeId,
                AuditAction.REPORT_BACKFILL_REQUESTED,
                new AuditTarget(AuditEntityType.REPORT_BACKFILL, job.getId()),
                "Administrative report backfill queued",
                null,
                summary(result)
        );
        return result;
    }

    @Transactional(readOnly = true)
    public ReportBackfillJobView get(UUID jobId) {
        return repository.findById(jobId)
                .map(ReportBackfillJobView::from)
                .orElseThrow(() -> new ReportBackfillJobNotFoundException(jobId));
    }

    @Transactional(readOnly = true)
    public List<ReportBackfillJobView> list(int limit) {
        if (limit < 1 || limit > 100) {
            throw new InvalidRequestException("limit must be between 1 and 100");
        }
        return repository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, limit))
                .stream()
                .map(ReportBackfillJobView::from)
                .toList();
    }

    private ReportBackfillJobView matching(
            ReportBackfillJob existing,
            UUID storeId,
            int year
    ) {
        if (!existing.getStore().getId().equals(storeId)
                || existing.getYear() != year) {
            throw new ReportBackfillIdempotencyConflictException();
        }
        return ReportBackfillJobView.from(existing);
    }

    private void validateYear(int year) {
        if (year < 2000 || year > 2100) {
            throw new InvalidRequestException(
                    "report backfill year is outside supported range"
            );
        }
    }

    private String validateIdempotencyKey(String value) {
        String key = value == null ? "" : value.trim();
        if (!IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new InvalidRequestException(
                    "Idempotency-Key must contain 8 to 100 safe characters"
            );
        }
        return key;
    }

    private Map<String, Object> summary(ReportBackfillJobView job) {
        return Map.of(
                "year", job.year(),
                "status", job.status(),
                "phase", job.phase(),
                "maxAttempts", job.maxAttempts()
        );
    }
}
