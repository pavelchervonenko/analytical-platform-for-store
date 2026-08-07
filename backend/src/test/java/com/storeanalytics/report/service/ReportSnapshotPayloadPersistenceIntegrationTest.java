package com.storeanalytics.report.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.model.UserRole;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.integration.connection.model.IntegrationConnection;
import com.storeanalytics.integration.connection.repository.IntegrationConnectionRepository;
import com.storeanalytics.metrics.model.ReportContent;
import com.storeanalytics.metrics.model.ReportDefinition;
import com.storeanalytics.metrics.model.ReportIntegrity;
import com.storeanalytics.metrics.model.ReportPeriodType;
import com.storeanalytics.metrics.model.ReportRevision;
import com.storeanalytics.metrics.model.ReportSnapshot;
import com.storeanalytics.metrics.model.ReportStatus;
import com.storeanalytics.metrics.model.ReportType;
import com.storeanalytics.metrics.repository.ReportSnapshotRepository;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.repository.StoreRepository;
import com.storeanalytics.sync.model.SourceSystem;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {
        "app.reports.backfill.worker-enabled=false",
        "app.reports.annual-scheduling-enabled=false",
        "app.sync.worker-enabled=false",
        "app.sync.schedule-enabled=false",
        "app.maintenance.retention.scheduling-enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class ReportSnapshotPayloadPersistenceIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private ReportSnapshotCodec codec;

    @Autowired
    private ReportSnapshotRepository reportRepository;

    @Autowired
    private IntegrationConnectionRepository connectionRepository;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @Transactional
    void preservesAndVerifiesExactPayloadAcrossPostgresRoundTrip() {
        IntegrationConnection connection = connectionRepository.save(
                new IntegrationConnection(
                        "report-payload-test",
                        SourceSystem.LIVESKLAD,
                        "Report payload test",
                        "https://example.invalid",
                        "env:TEST_CREDENTIALS"
                )
        );
        Store store = storeRepository.save(Store.fromLiveSklad(
                connection,
                "report-payload-store",
                "Report payload store",
                "Test address"
        ));
        AppUser actor = userRepository.save(new AppUser(
                "report-payload@example.test",
                "test-password-hash",
                "Report Payload Administrator",
                UserRole.ADMIN
        ));
        Instant generatedAt = Instant.parse("2026-01-01T10:00:00Z");
        AnnualReportPayload payload = new AnnualReportPayload(
                1,
                new ReportHeader(
                        store.getId(),
                        store.getName(),
                        store.getAddress(),
                        LocalDate.of(2025, 1, 1),
                        LocalDate.of(2025, 1, 1),
                        LocalDate.of(2025, 12, 31),
                        ReportCoverageStatus.COMPLETE,
                        "annual-report-test-v1",
                        "report-data-test-v1",
                        generatedAt,
                        new ReportActorView(actor.getId(), actor.getDisplayName())
                ),
                new AnnualStoreTotals(
                        12,
                        new BigDecimal("123456.78"),
                        new BigDecimal("42.500"),
                        new BigDecimal("100000.00"),
                        new BigDecimal("23456.78"),
                        new BigDecimal("19.0000"),
                        new BigDecimal("10000.00"),
                        new BigDecimal("9000.00")
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        EncodedReport encoded = codec.encode(payload);
        ReportSnapshot saved = reportRepository.saveAndFlush(new ReportSnapshot(
                store,
                new ReportDefinition(
                        ReportType.ANNUAL,
                        ReportPeriodType.YEAR,
                        LocalDate.of(2025, 1, 1),
                        LocalDate.of(2025, 12, 31),
                        ReportStatus.FINALIZED,
                        "annual-report-test-v1",
                        "report-data-test-v1"
                ),
                new ReportContent(
                        new ReportIntegrity(
                                codec.sourceHash(Map.of("source", "test")),
                                encoded.sha256()
                        ),
                        encoded.payload(),
                        generatedAt,
                        actor,
                        generatedAt,
                        actor
                ),
                new ReportRevision(1, null, null, null, 1)
        ));
        UUID snapshotId = saved.getId();

        entityManager.flush();
        entityManager.clear();

        ReportSnapshot reloaded = reportRepository.findById(snapshotId)
                .orElseThrow();
        assertThat(reloaded.getPayload()).isEqualTo(encoded.payload());
        assertThat(reloaded.getPayloadHash()).isEqualTo(encoded.sha256());
        assertThat(codec.decodeAnnual(reloaded)).isEqualTo(payload);
    }

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
