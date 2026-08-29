package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.interpretation.review.WeeklyReviewFacts.PeriodFacts;
import com.storeanalytics.interpretation.review.WeeklyReviewPolicyV1.RevenuePeriod;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.DateRange;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.PeriodContext;
import com.storeanalytics.interpretation.snapshot.EmployeeSalesSampleFacts;
import com.storeanalytics.metrics.service.AttachRateDataQuality;
import com.storeanalytics.metrics.service.AttachRateResult;
import com.storeanalytics.metrics.service.CategoryKpiDataQuality;
import com.storeanalytics.metrics.service.CategoryKpiGroup;
import com.storeanalytics.metrics.service.CategoryKpiMetrics;
import com.storeanalytics.metrics.service.CategoryKpiResult;
import com.storeanalytics.metrics.service.StoreKpiDataQuality;
import com.storeanalytics.metrics.service.StoreKpiResult;
import com.storeanalytics.performance.service.EmployeeRatingResult;
import com.storeanalytics.store.service.StoreDataFreshnessStatus;
import com.storeanalytics.store.service.StoreDataStatusView;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class WeeklyReviewSnapshotStoreIntegrationTest {

    private static final DateRange CURRENT = new DateRange(
            LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 23)
    );
    private static final DateRange PREVIOUS = new DateRange(
            LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 16)
    );
    private static final PeriodContext PERIOD = new PeriodContext(
            "Europe/Kaliningrad",
            CURRENT,
            PREVIOUS,
            "17–23 августа 2026",
            "10–16 августа 2026"
    );

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private WeeklyReviewSnapshotStore store;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void reusesEqualContentCreatesRevisionForChangesAndRejectsMutation() {
        UUID storeId = addStore();
        WeeklyReviewFacts zero = facts(storeId, "0.00", "0.00");

        PersistedWeeklyReviewSnapshot first = store.persist(
                zero, Instant.parse("2026-08-24T04:00:00Z")
        );
        PersistedWeeklyReviewSnapshot reused = store.persist(
                zero, Instant.parse("2026-08-24T04:05:00Z")
        );
        PersistedWeeklyReviewSnapshot revision = store.persist(
                facts(storeId, "100.00", "0.00"),
                Instant.parse("2026-08-24T04:10:00Z")
        );

        assertThat(first.revision()).isOne();
        assertThat(reused.id()).isEqualTo(first.id());
        assertThat(reused.revision()).isOne();
        assertThat(revision.revision()).isEqualTo(2);
        assertThat(revision.supersedesSnapshotId()).isEqualTo(first.id());
        assertThat(revision.response().provenance().revisionChanged()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM weekly_review_snapshots WHERE store_id = ?",
                Long.class,
                storeId
        )).isEqualTo(2L);
        assertThat(store.findLatest(storeId, CURRENT))
                .get()
                .extracting(PersistedWeeklyReviewSnapshot::id)
                .isEqualTo(revision.id());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE weekly_review_snapshots SET report_state = 'READY' WHERE id = ?",
                revision.id()
        )).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("Weekly review snapshots are immutable");
    }

    @Test
    void rejectsPayloadWithoutMatchingImmutableHeader() {
        UUID storeId = addStore();
        UUID snapshotId = UUID.randomUUID();

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO weekly_review_snapshots (
                    id, store_id, period_start, period_end, timezone, revision,
                    report_contract_version, metrics_policy_version,
                    snapshot_policy_version, quality_policy_version,
                    report_state, report_payload, content_hash
                ) VALUES (
                    ?, ?, ?, ?, 'Europe/Kaliningrad', 1, 2,
                    'weekly-metrics-v4', 'weekly-snapshot-v7',
                    'weekly-quality-v4', 'READY', CAST('{}' AS jsonb), ?
                )
                """,
                snapshotId,
                storeId,
                CURRENT.start(),
                CURRENT.end(),
                "a".repeat(64)
        )).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(
                        "ck_weekly_review_snapshot_payload_header"
                );
    }

    private UUID addStore() {
        UUID connectionId = jdbcTemplate.queryForObject(
                "SELECT id FROM integration_connections WHERE connection_key = 'livesklad-default'",
                UUID.class
        );
        UUID storeId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO stores (
                    id, connection_id, source_system, external_id, name, timezone
                ) VALUES (?, ?, 'LIVESKLAD', ?, 'Weekly review snapshot store',
                    'Europe/Kaliningrad')
                """,
                storeId,
                connectionId,
                "weekly-review-snapshot-" + storeId
        );
        return storeId;
    }

    private WeeklyReviewFacts facts(UUID storeId, String currentRevenue, String previousRevenue) {
        return new WeeklyReviewFacts(
                storeId,
                PERIOD,
                status(storeId),
                periodFacts(storeId, currentRevenue, CURRENT),
                periodFacts(storeId, previousRevenue, PREVIOUS),
                Instant.parse("2026-08-24T03:50:00Z")
        );
    }

    private PeriodFacts periodFacts(UUID storeId, String revenue, DateRange period) {
        BigDecimal amount = new BigDecimal(revenue);
        StoreKpiDataQuality quality = new StoreKpiDataQuality(
                true, 0, 0, 0, 0, 0, 0
        );
        StoreKpiResult storeKpi = new StoreKpiResult(
                storeId,
                period.start(),
                period.end(),
                "store-kpi-v3",
                amount,
                BigDecimal.ZERO.setScale(3),
                BigDecimal.ZERO.setScale(2),
                amount,
                amount.signum() <= 0 ? null : new BigDecimal("100.00"),
                quality
        );
        CategoryKpiResult categories = categories(storeId, period);
        AttachRateResult attach = new AttachRateResult(
                storeId,
                period.start(),
                period.end(),
                "attach-rate-v3",
                new AttachRateDataQuality(0, 0, 0),
                List.of()
        );
        EmployeeRatingResult employees = new EmployeeRatingResult(
                storeId,
                period.start(),
                period.end(),
                null,
                null,
                List.of(),
                null
        );
        return new PeriodFacts(
                storeKpi,
                categories,
                attach,
                employees,
                new EmployeeSalesSampleFacts(Map.of()),
                0,
                new RevenuePeriod(amount, BigDecimal.ZERO.setScale(2), amount, 0, 0)
        );
    }

    private CategoryKpiResult categories(UUID storeId, DateRange period) {
        CategoryKpiMetrics zero = new CategoryKpiMetrics(
                BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(3),
                BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(2),
                null,
                null,
                new CategoryKpiDataQuality(true, 0, 0, 0)
        );
        return new CategoryKpiResult(
                storeId,
                period.start(),
                period.end(),
                "category-kpi-v3",
                List.of(
                        new CategoryKpiGroup("PHONES", "Телефоны", zero),
                        new CategoryKpiGroup("DEVICES", "Устройства", zero),
                        new CategoryKpiGroup("ACCESSORY", "Аксессуары", zero),
                        new CategoryKpiGroup("SERVICE", "Услуги", zero),
                        new CategoryKpiGroup(
                                "ADDITIONAL_REVENUE", "Дополнительная выручка", zero
                        )
                ),
                List.of()
        );
    }

    private StoreDataStatusView status(UUID storeId) {
        return new StoreDataStatusView(
                storeId,
                StoreDataFreshnessStatus.CURRENT,
                CURRENT.end(),
                CURRENT.end(),
                CURRENT.end(),
                CURRENT.end(),
                0,
                Instant.parse("2026-08-24T03:50:00Z"),
                null,
                0,
                null,
                null,
                Instant.parse("2026-08-26T12:00:00Z")
        );
    }
}
