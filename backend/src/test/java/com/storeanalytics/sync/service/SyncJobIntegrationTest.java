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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = "app.sync.worker-enabled=false")
@Testcontainers(disabledWithoutDocker = true)
class SyncJobIntegrationTest {

    private static final String WORKER = "sync-job-test-worker";

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

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

        SyncJobView cancelled = coordinator.cancel(first.id(), admin.getId());
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
    @Test
    void cancelsWaitingRetryImmediatelyAndClearsOperatorError() {
        SyncJobView created = createOneDayJob();
        SyncJobClaim claim = coordinator.claimNext(WORKER).orElseThrow();
        coordinator.retryOrFail(
                claim.jobId(),
                WORKER,
                "Sanitized retry reason",
                true,
                Duration.ofHours(1)
        );

        SyncJobView cancelled = coordinator.cancel(created.id(), admin.getId());

        assertThat(cancelled.status()).isEqualTo(SyncJobStatus.CANCELLED);
        assertThat(cancelled.cancelRequested()).isTrue();
        assertThat(cancelled.errorSummary()).isNull();
        assertThat(cancelled.finishedAt()).isNotNull();
        assertThat(createOneDayJob().status()).isEqualTo(SyncJobStatus.PENDING);
    }

    @Test
    void runningCancellationRemainsExclusiveUntilWorkerReachesBoundary() {
        SyncJobView created = createOneDayJob();
        SyncJobClaim claim = coordinator.claimNext(WORKER).orElseThrow();

        SyncJobView cancellationRequested =
                coordinator.cancel(created.id(), admin.getId());

        assertThat(cancellationRequested.status()).isEqualTo(SyncJobStatus.RUNNING);
        assertThat(cancellationRequested.cancelRequested()).isTrue();
        assertThatThrownBy(this::createOneDayJob)
                .isInstanceOf(ActiveSyncJobException.class);

        coordinator.completeStep(claim.jobId(), WORKER);

        assertThat(jobService.get(created.id()).status())
                .isEqualTo(SyncJobStatus.CANCELLED);
        assertThat(createOneDayJob().status()).isEqualTo(SyncJobStatus.PENDING);
    }

    @Test
    void recoversExpiredLeaseAndAllowsAnotherWorkerToResume() {
        SyncJobView created = createOneDayJob();
        coordinator.claimNext(WORKER).orElseThrow();
        jdbcTemplate.update(
                "UPDATE sync_jobs SET lease_until = now() - interval '1 second' WHERE id = ?",
                created.id()
        );

        assertThat(coordinator.claimNext("replacement-worker")).isEmpty();

        SyncJobView recovered = jobService.get(created.id());
        assertThat(recovered.status()).isEqualTo(SyncJobStatus.WAITING_RETRY);
        assertThat(recovered.attemptCount()).isEqualTo(1);
        assertThat(recovered.errorSummary()).isEqualTo(
                "Synchronization worker lease expired"
        );

        jdbcTemplate.update(
                "UPDATE sync_jobs SET next_attempt_at = now() - interval '1 second' WHERE id = ?",
                created.id()
        );
        SyncJobClaim replacement = coordinator.claimNext(
                "replacement-worker"
        ).orElseThrow();
        assertThat(replacement.attemptCount()).isEqualTo(1);

        coordinator.completeStep(replacement.jobId(), "replacement-worker");

        SyncJobView resumed = jobService.get(created.id());
        assertThat(resumed.status()).isEqualTo(SyncJobStatus.PENDING);
        assertThat(resumed.phase()).isEqualTo(SyncJobPhase.EMPLOYEES);
    }

    @Test
    void boundsPersistedOperatorErrorSummary() {
        SyncJobView created = createOneDayJob();
        SyncJobClaim claim = coordinator.claimNext(WORKER).orElseThrow();

        coordinator.retryOrFail(
                claim.jobId(),
                WORKER,
                "x".repeat(400),
                false,
                Duration.ZERO
        );

        SyncJobView failed = jobService.get(created.id());
        assertThat(failed.status()).isEqualTo(SyncJobStatus.FAILED);
        assertThat(failed.errorSummary()).hasSize(300);
    }
    @Test
    void onlyOneWorkerClaimsAJobWhenWorkersRace() throws Exception {
        SyncJobView created = createOneDayJob();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Optional<SyncJobClaim>> first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return coordinator.claimNext("worker-one");
            });
            Future<Optional<SyncJobClaim>> second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return coordinator.claimNext("worker-two");
            });

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Optional<SyncJobClaim>> claims = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS)
            );

            assertThat(claims.stream().filter(Optional::isPresent).toList())
                    .hasSize(1);
        }

        assertThat(jobService.get(created.id()).status())
                .isEqualTo(SyncJobStatus.RUNNING);
    }

    private SyncJobView createOneDayJob() {
        return jobService.createBackfill(
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 1),
                admin.getId()
        );
    }
}
