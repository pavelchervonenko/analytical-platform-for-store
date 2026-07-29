package com.storeanalytics.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeanalytics.audit.service.AuditLogService;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.common.config.ReportBackfillProperties;
import com.storeanalytics.report.exception.ActiveReportBackfillJobException;
import com.storeanalytics.report.exception.ReportBackfillIdempotencyConflictException;
import com.storeanalytics.report.model.ReportBackfillJob;
import com.storeanalytics.report.model.ReportBackfillJobPhase;
import com.storeanalytics.report.model.ReportBackfillJobStatus;
import com.storeanalytics.report.repository.ReportBackfillJobRepository;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.repository.StoreRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ReportBackfillJobServiceTest {

    private final ReportBackfillJobRepository repository = mock(
            ReportBackfillJobRepository.class
    );
    private final StoreRepository storeRepository = mock(StoreRepository.class);
    private final AppUserRepository userRepository = mock(AppUserRepository.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final UUID actorId = UUID.randomUUID();
    private final UUID storeId = UUID.randomUUID();
    private ReportBackfillJobService service;

    @BeforeEach
    void setUp() {
        service = new ReportBackfillJobService(
                repository,
                storeRepository,
                userRepository,
                new ReportBackfillProperties(
                        3,
                        Duration.ofMinutes(30),
                        Duration.ofSeconds(30),
                        Duration.ofMinutes(15),
                        20
                ),
                jdbcTemplate,
                Clock.systemUTC(),
                auditLogService
        );
    }

    @Test
    void sameIdempotencyKeyReturnsOriginalJobWithoutAnotherAudit() {
        ReportBackfillJob existing = existingJob(storeId, 2025);
        when(repository.findByRequestedByIdAndIdempotencyKey(
                actorId,
                "request-12345678"
        )).thenReturn(Optional.of(existing));

        ReportBackfillJobView result = service.create(
                storeId,
                2025,
                actorId,
                "request-12345678"
        );

        assertThat(result.storeId()).isEqualTo(storeId);
        assertThat(result.year()).isEqualTo(2025);
        verify(jdbcTemplate, never()).execute(any(String.class));
        verify(repository, never()).saveAndFlush(any());
        verify(auditLogService, never()).record(
                any(), any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    void reusedIdempotencyKeyCannotChangeRequestIdentity() {
        ReportBackfillJob existing = existingJob(storeId, 2025);
        when(repository.findByRequestedByIdAndIdempotencyKey(
                actorId,
                "request-12345678"
        )).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.create(
                storeId,
                2024,
                actorId,
                "request-12345678"
        )).isInstanceOf(ReportBackfillIdempotencyConflictException.class);
    }

    @Test
    void oneActiveJobPerStoreIsEnforcedBeforeLoadingEntities() {
        when(repository.existsByStoreIdAndStatusIn(any(), any()))
                .thenReturn(true);

        assertThatThrownBy(() -> service.create(
                storeId,
                2025,
                actorId,
                "request-12345678"
        )).isInstanceOf(ActiveReportBackfillJobException.class);

        verify(storeRepository, never()).findById(any());
        verify(userRepository, never()).findById(any());
    }

    private ReportBackfillJob existingJob(UUID existingStoreId, int year) {
        Store store = mock(Store.class);
        when(store.getId()).thenReturn(existingStoreId);
        ReportBackfillJob job = mock(ReportBackfillJob.class);
        when(job.getStore()).thenReturn(store);
        when(job.getYear()).thenReturn(year);
        when(job.getStatus()).thenReturn(ReportBackfillJobStatus.PENDING);
        when(job.getPhase()).thenReturn(ReportBackfillJobPhase.MONTHLY);
        when(job.getNextAttemptAt()).thenReturn(Clock.systemUTC().instant());
        return job;
    }
}
