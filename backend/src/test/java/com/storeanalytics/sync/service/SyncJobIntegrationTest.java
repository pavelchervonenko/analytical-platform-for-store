package com.storeanalytics.sync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.model.UserRole;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.integration.connection.model.IntegrationConnection;
import com.storeanalytics.integration.connection.repository.IntegrationConnectionRepository;
import com.storeanalytics.sync.model.SyncRun;
import com.storeanalytics.sync.model.SyncTriggerType;
import com.storeanalytics.sync.repository.SyncRunRepository;
import com.storeanalytics.sync.exception.ActiveSyncJobException;
import com.storeanalytics.sync.model.SyncJobPhase;
import com.storeanalytics.sync.model.SyncJobStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = "app.sync.worker-enabled=false")
@Testcontainers(disabledWithoutDocker = true)
class SyncJobIntegrationTest {

    private static final String WORKER = "sync-job-test-worker";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private SyncJobService jobService;

    @Autowired
    private SyncJobCoordinator coordinator;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IntegrationConnectionRepository connectionRepository;

    @Autowired
    private SyncRunRepository syncRunRepository;

    private AppUser admin;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM sync_run_errors");
        jdbcTemplate.update("DELETE FROM sync_runs");
        jdbcTemplate.update("DELETE FROM sync_jobs");
        jdbcTemplate.update("DELETE FROM user_store_access");
        jdbcTemplate.update("DELETE FROM app_users");
        admin = userRepository.saveAndFlush(new AppUser(
                "sync-admin@example.invalid",
                "{noop}test-password-hash",
                "Sync Admin",
                UserRole.ADMIN
        ));
    }

    @Test
    void completesDurablePhasesAndCalendarWindowInOrder() {
        SyncJobView created = jobService.createBackfill(
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 1),
                admin.getId()
        );

        assertThat(created.status()).isEqualTo(SyncJobStatus.PENDING);
        assertThat(created.periodStart()).isEqualTo(Instant.parse("2025-12-31T22:00:00Z"));
        assertThat(created.periodEnd()).isEqualTo(Instant.parse("2026-01-01T22:00:00Z"));

        List<SyncJobPhase> expected = List.of(
                SyncJobPhase.STORES,
                SyncJobPhase.EMPLOYEES,
                SyncJobPhase.SALES,
                SyncJobPhase.RETURNS
        );
        for (SyncJobPhase phase : expected) {
            SyncJobClaim claim = coordinator.claimNext(WORKER).orElseThrow();
            assertThat(claim.phase()).isEqualTo(phase);
            coordinator.completeStep(claim.jobId(), WORKER);
        }

        SyncJobView completed = jobService.get(created.id());
        assertThat(completed.status()).isEqualTo(SyncJobStatus.SUCCESS);
        assertThat(completed.completedSteps()).isEqualTo(4);
        assertThat(completed.cursorStart()).isEqualTo(completed.periodEnd());
        assertThat(completed.finishedAt()).isNotNull();
    }

    @Test
    void childRunKeepsDurableLinkToParentJob() {
        SyncJobView job = createOneDayJob();
        IntegrationConnection connection =
                connectionRepository.findById(job.connectionId()).orElseThrow();
        SyncRun run = SyncRun.startStoreSync(
                connection,
                SyncTriggerType.INITIAL,
                job.id(),
                admin,
                Instant.now()
        );

        syncRunRepository.saveAndFlush(run);

        assertThat(run.getSyncJobId()).isEqualTo(job.id());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT sync_job_id FROM sync_runs WHERE id = ?",
                UUID.class,
                run.getId()
        )).isEqualTo(job.id());
    }

    @Test
    void rejectsSecondActiveJobAndReleasesConstraintAfterCancellation() {
        SyncJobView first = createOneDayJob();

        assertThatThrownBy(this::createOneDayJob)
                .isInstanceOf(ActiveSyncJobException.class);

        SyncJobView cancelled = coordinator.cancel(first.id());
        assertThat(cancelled.status()).isEqualTo(SyncJobStatus.CANCELLED);
        assertThat(createOneDayJob().status()).isEqualTo(SyncJobStatus.PENDING);
    }

    @Test
    void halvesOverCapacityWindowWithoutConsumingRetryAttempt() {
        SyncJobView created = createOneDayJob();
        SyncJobClaim claim = coordinator.claimNext(WORKER).orElseThrow();
        coordinator.completeStep(claim.jobId(), WORKER);
        claim = coordinator.claimNext(WORKER).orElseThrow();
        coordinator.completeStep(claim.jobId(), WORKER);
        claim = coordinator.claimNext(WORKER).orElseThrow();

        Duration original = Duration.between(claim.windowStart(), claim.windowEnd());
        assertThat(coordinator.shrinkWindow(claim.jobId(), WORKER)).isTrue();

        SyncJobView resized = jobService.get(created.id());
        assertThat(Duration.between(
                resized.cursorStart(),
                resized.currentWindowEnd()
        )).isEqualTo(original.dividedBy(2));
        assertThat(resized.status()).isEqualTo(SyncJobStatus.PENDING);
        assertThat(resized.attemptCount()).isZero();
    }

    @Test
    void retriesTransientStepAndEventuallyFailsAtConfiguredLimit() {
        SyncJobView created = createOneDayJob();

        for (int attempt = 1; attempt <= 5; attempt++) {
            SyncJobClaim claim = coordinator.claimNext(WORKER).orElseThrow();
            coordinator.retryOrFail(
                    claim.jobId(),
                    WORKER,
                    "Sanitized test failure",
                    true,
                    Duration.ZERO
            );
        }

        SyncJobView failed = jobService.get(created.id());
        assertThat(failed.status()).isEqualTo(SyncJobStatus.FAILED);
        assertThat(failed.attemptCount()).isEqualTo(5);
        assertThat(failed.totalRetries()).isEqualTo(5);
        assertThat(failed.errorSummary()).isEqualTo("Sanitized test failure");
    }

    private SyncJobView createOneDayJob() {
        return jobService.createBackfill(
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 1),
                admin.getId()
        );
    }
}
