package com.storeanalytics.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.audit.repository.AuditLogRepository;
import com.storeanalytics.audit.service.AuditAction;
import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.model.UserRole;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.integration.connection.model.IntegrationConnection;
import com.storeanalytics.integration.connection.repository.IntegrationConnectionRepository;
import com.storeanalytics.report.exception.ActiveReportBackfillJobException;
import com.storeanalytics.report.model.ReportBackfillJobStatus;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.repository.StoreRepository;
import com.storeanalytics.sync.model.SourceSystem;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
        "app.reports.backfill.worker-enabled=false",
        "app.reports.annual-scheduling-enabled=false",
        "app.sync.worker-enabled=false",
        "app.sync.schedule-enabled=false",
        "app.maintenance.retention.scheduling-enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class ReportBackfillJobPersistenceIntegrationTest {

    private static final String IDEMPOTENCY_KEY = "integration-request-12345678";

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private ReportBackfillJobService jobService;

    @Autowired
    private ReportBackfillJobCoordinator coordinator;

    @Autowired
    private IntegrationConnectionRepository connectionRepository;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void persistsIdempotentClaimsRetriesAndCancellation() {
        IntegrationConnection connection = connectionRepository.save(
                new IntegrationConnection(
                        "report-backfill-test",
                        SourceSystem.LIVESKLAD,
                        "Report backfill test",
                        "https://example.invalid",
                        "env:TEST_CREDENTIALS"
                )
        );
        Store store = storeRepository.save(Store.fromLiveSklad(
                connection,
                "report-backfill-store",
                "Report backfill store",
                "Test address"
        ));
        AppUser actor = userRepository.save(new AppUser(
                "report-backfill@example.test",
                "test-password-hash",
                "Report Backfill Administrator",
                UserRole.ADMIN
        ));

        ReportBackfillJobView created = jobService.create(
                store.getId(),
                2025,
                actor.getId(),
                IDEMPOTENCY_KEY
        );
        ReportBackfillJobView replay = jobService.create(
                store.getId(),
                2025,
                actor.getId(),
                IDEMPOTENCY_KEY
        );

        assertThat(replay.id()).isEqualTo(created.id());
        assertThatThrownBy(() -> jobService.create(
                store.getId(),
                2025,
                actor.getId(),
                "integration-request-87654321"
        )).isInstanceOf(ActiveReportBackfillJobException.class);

        ReportBackfillJobClaim claim = coordinator.claimNext("test-worker")
                .orElseThrow();
        assertThat(claim.jobId()).isEqualTo(created.id());
        assertThat(jobService.get(created.id()).status())
                .isEqualTo(ReportBackfillJobStatus.RUNNING);

        coordinator.retryOrFail(
                created.id(),
                "test-worker",
                "Transient database failure",
                true,
                Duration.ofSeconds(1)
        );
        ReportBackfillJobView waiting = jobService.get(created.id());
        assertThat(waiting.status())
                .isEqualTo(ReportBackfillJobStatus.WAITING_RETRY);
        assertThat(waiting.totalRetries()).isOne();
        assertThat(waiting.leaseUntil()).isNull();

        coordinator.cancel(created.id(), actor.getId());
        coordinator.cancel(created.id(), actor.getId());

        assertThat(jobService.get(created.id()).status())
                .isEqualTo(ReportBackfillJobStatus.CANCELLED);
        assertThat(auditLogRepository.findAll())
                .extracting(log -> log.getAction())
                .containsExactlyInAnyOrder(
                        AuditAction.REPORT_BACKFILL_REQUESTED.name(),
                        AuditAction.REPORT_BACKFILL_CANCELLATION_REQUESTED.name()
                );
    }
}
