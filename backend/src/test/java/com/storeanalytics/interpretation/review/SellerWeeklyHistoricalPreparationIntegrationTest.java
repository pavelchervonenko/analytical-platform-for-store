package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real queue -> RR financial/attach/history -> fenced immutable writer -> token binding. */
@SpringBootTest(properties = "app.attach.attribution-enabled=true")
@Testcontainers(disabledWithoutDocker = true)
class SellerWeeklyHistoricalPreparationIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @TestConfiguration
    static class ClockConfiguration {
        @Bean
        @Primary
        Clock historicalPreparationClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private SellerWeeklyPreparationStore queue;
    @Autowired
    private SellerWeeklyPreparationRunner runner;
    @Autowired
    private WeeklyReviewSnapshotStore snapshots;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void disableOnlySyntheticStores() {
        jdbc.update("UPDATE stores SET is_active=false WHERE name='Synthetic historical preparation'");
    }

    @Test
    void recoversThreeClosedWeeksWithDepartedMembershipAndNoPaidMutationAcrossRefresh() {
        UUID store = seed(true);
        assertThat(queue.discover(store, NOW, 52).insertedWeeks()).isEqualTo(3);
        List<UUID> saved = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            var result = runner.prepareNext("synthetic-owner");
            assertThat(result.state()).isEqualTo("SUCCEEDED");
            saved.add(result.snapshotId());
        }
        assertThat(runner.prepareNext("synthetic-owner").state()).isEqualTo("IDLE");
        assertThat(jdbc.queryForList("SELECT period_start::text FROM weekly_review_snapshots "
                + "WHERE store_id=? ORDER BY period_start", String.class, store))
                .containsExactly("2026-09-14", "2026-09-21", "2026-09-28");
        var first = snapshots.findV3ById(saved.getFirst()).orElseThrow().response();
        assertThat(first.membership().basis()).isEqualTo(SellerWeeklyHistoricalMembership.BASIS);
        assertThat(first.employees()).singleElement().satisfies(item -> {
            assertThat(item.actionableNow()).isFalse();
            assertThat(item.card().action()).isNull();
            assertThat(item.card().limitations()).anyMatch(value -> value.startsWith("Не в текущей команде"));
        });
        assertThat(first.actions()).isEmpty();
        var oldPayloads = jdbc.queryForList("SELECT report_payload::text FROM weekly_review_snapshots "
                + "WHERE store_id=? ORDER BY period_start", String.class, store);
        jdbc.update("UPDATE store_analytics_source_state SET revision=revision+1 WHERE store_id=?", store);
        assertThat(queue.requeueStaleSnapshots(100, NOW)).isEqualTo(3);
        for (int index = 0; index < 3; index++) {
            assertThat(runner.prepareNext("after-restart").state()).isEqualTo("SUCCEEDED");
        }
        assertThat(jdbc.queryForList("SELECT snapshot_id FROM seller_weekly_preparation_jobs "
                + "WHERE store_id=? ORDER BY period_start", UUID.class, store)).containsExactlyElementsOf(saved);
        assertThat(jdbc.queryForList("SELECT report_payload::text FROM weekly_review_snapshots "
                + "WHERE store_id=? ORDER BY period_start", String.class, store)).isEqualTo(oldPayloads);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM weekly_review_ai_jobs", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM weekly_review_ai_response_receipts", Long.class)).isZero();
    }

    @Test
    void incompleteOldSourceRemainsDelayedWithoutAnIncompleteSnapshotOrFallback() {
        UUID store = seed(false);
        queue.discover(store, NOW, 1);
        assertThat(runner.prepareNext("synthetic-owner").state()).isEqualTo("WAITING_SOURCES");
        assertThat(jdbc.queryForObject("SELECT last_reason_code FROM seller_weekly_preparation_jobs WHERE store_id=?",
                String.class, store)).isEqualTo("SOURCE_COVERAGE_INCOMPLETE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM weekly_review_snapshots WHERE store_id=?",
                Long.class, store)).isZero();
        assertThat(runner.prepareNext("after-restart").state()).isEqualTo("IDLE");
    }

    private UUID seed(boolean complete) {
        UUID connection = jdbc.queryForObject("SELECT id FROM integration_connections "
                + "WHERE connection_key='livesklad-default'", UUID.class);
        UUID store = UUID.randomUUID();
        UUID employee = UUID.randomUUID();
        jdbc.update("INSERT INTO stores(id,connection_id,source_system,external_id,name,timezone) "
                + "VALUES (?,?,'LIVESKLAD',?,'Synthetic historical preparation','UTC')",
                store, connection, store.toString());
        jdbc.update("INSERT INTO employees(id,connection_id,source_system,external_id,full_name,is_active) "
                + "VALUES (?,?,'LIVESKLAD',?,'Synthetic former seller',false)",
                employee, connection, employee.toString());
        jdbc.update("INSERT INTO store_seller_membership_state(store_id,authoritative_from,baseline_source) "
                + "VALUES (?,'2026-09-07T00:00:00Z','SYNTHETIC_ONLY')", store);
        jdbc.update("""
                INSERT INTO seller_membership_history(store_id,employee_id,employee_active,assignment_active,
                    participates_in_ranking,valid_from,valid_to,change_source,effective_time_source)
                VALUES (?,?,true,true,true,'2026-09-07T00:00:00Z','2026-09-16T00:00:00Z',
                    'BASELINE','APPROVED_BASELINE')
                """, store, employee);
        jdbc.update("""
                INSERT INTO seller_membership_history(store_id,employee_id,employee_active,assignment_active,
                    participates_in_ranking,valid_from,change_source,effective_time_source)
                VALUES (?,?,false,false,true,'2026-09-16T00:00:00Z','BASELINE','APPROVED_BASELINE')
                """, store, employee);
        for (String scope : complete ? List.of("SALES", "RETURNS", "ORDERS") : List.of("SALES", "ORDERS")) {
            jdbc.update("""
                    INSERT INTO sync_runs(id,connection_id,store_id,source_system,trigger_type,sync_scope,status,
                        period_start,period_end,started_at,finished_at)
                    VALUES (?,?,?,'LIVESKLAD','MANUAL',?,'SUCCESS','2026-09-07T00:00:00Z',
                        '2026-10-05T00:00:00Z',?,?)
                    """, UUID.randomUUID(), connection, store, scope,
                    Timestamp.from(NOW.minusSeconds(60)), Timestamp.from(NOW.minusSeconds(30)));
        }
        return store;
    }
}
