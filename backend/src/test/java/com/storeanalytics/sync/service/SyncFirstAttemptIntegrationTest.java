package com.storeanalytics.sync.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.integration.connection.repository.IntegrationConnectionRepository;
import com.storeanalytics.integration.livesklad.dto.LiveSkladStorePayload;
import com.storeanalytics.sync.model.SyncJob;
import com.storeanalytics.sync.model.SyncJobDefinition;
import com.storeanalytics.sync.model.SyncJobPhase;
import com.storeanalytics.sync.model.SyncJobType;
import com.storeanalytics.sync.repository.SyncJobRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"app.sync.worker-enabled=false", "app.sync.schedule-enabled=false"})
@Testcontainers(disabledWithoutDocker = true)
@Import(StoreSyncIntegrationTest.FakeClientConfiguration.class)
class SyncFirstAttemptIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private IntegrationConnectionRepository connections;
    @Autowired
    private SyncJobRepository jobs;
    @Autowired
    private SyncJobCoordinator coordinator;
    @Autowired
    private SyncJobExecutionService execution;
    @Autowired
    private StoreSyncIntegrationTest.FakeLiveSkladClient fake;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void firstScheduledJobExecutesRealStoreAndEmployeeServicesWithZeroLeaseFence() {
        var connection = connections.findByConnectionKeyAndActiveTrue("livesklad-default").orElseThrow();
        fake.setStores(List.of(new LiveSkladStorePayload(
                "first-attempt-store", "Synthetic first attempt store", null, null,
                json.createObjectNode().put("id", "first-attempt-store").put("name", "Synthetic store"))));
        Instant start = Instant.parse("2026-09-30T22:00:00Z");
        Instant end = Instant.parse("2026-10-01T22:00:00Z");
        SyncJob job = jobs.saveAndFlush(SyncJob.create(new SyncJobDefinition(
                connection, null, SyncJobType.INCREMENTAL, start, end, Duration.ofHours(6), 5), Instant.now()));
        String owner = "first-attempt-regression-worker";

        for (SyncJobPhase phase : List.of(SyncJobPhase.STORES, SyncJobPhase.EMPLOYEES)) {
            SyncJobClaim claim = coordinator.claimNext(owner).orElseThrow();
            assertThat(claim.jobId()).isEqualTo(job.getId());
            assertThat(claim.phase()).isEqualTo(phase);
            assertThat(claim.attemptCount()).isZero();
            execution.execute(claim);
            coordinator.completeStep(claim.jobId(), owner);
        }

        assertThat(jobs.findById(job.getId()).orElseThrow().getPhase()).isEqualTo(SyncJobPhase.SALES);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sync_runs WHERE sync_job_id=? AND status='SUCCESS'",
                Integer.class, job.getId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM sync_jobs WHERE id=?",
                Integer.class, job.getId())).isZero();
    }
}
