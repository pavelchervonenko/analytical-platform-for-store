package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.model.UserRole;
import com.storeanalytics.auth.model.UserStoreAccess;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.auth.repository.UserStoreAccessRepository;
import com.storeanalytics.store.repository.StoreRepository;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** Real queue -> RR financial/attach/history -> fenced immutable writer -> token binding. */
@SpringBootTest(properties = {"app.attach.attribution-enabled=true",
        "app.interpretation.weekly-review.enabled=true", "app.interpretation.seller-weekly-review.enabled=true"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
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
    private SellerWeeklyHistoricalReadService reads;
    @Autowired
    private SellerWeeklyHistoricalPlanningService planner;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private UserStoreAccessRepository accesses;
    @Autowired
    private StoreRepository stores;
    @Autowired
    private PasswordEncoder passwords;

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
        var start = LocalDate.parse("2026-09-14");
        assertThat(reads.assess(store, start).state()).isEqualTo(SellerWeeklyV3ReadResult.State.CURRENT);
        jdbc.update("UPDATE store_analytics_source_state SET revision=revision+1 WHERE store_id=?", store);
        assertThat(reads.assess(store, start).state()).isEqualTo(SellerWeeklyV3ReadResult.State.STALE);
        assertThat(queue.requeueStaleSnapshots(100, NOW)).isEqualTo(3);
        for (int index = 0; index < 3; index++) {
            assertThat(runner.prepareNext("after-restart").state()).isEqualTo("SUCCEEDED");
        }
        assertThat(jdbc.queryForList("SELECT snapshot_id FROM seller_weekly_preparation_jobs "
                + "WHERE store_id=? ORDER BY period_start", UUID.class, store)).containsExactlyElementsOf(saved);
        assertThat(jdbc.queryForList("SELECT report_payload::text FROM weekly_review_snapshots "
                + "WHERE store_id=? ORDER BY period_start", String.class, store)).isEqualTo(oldPayloads);
        for (var week : List.of(start, start.plusWeeks(1), start.plusWeeks(2))) {
            assertThat(reads.assess(store, week).state()).isEqualTo(SellerWeeklyV3ReadResult.State.CURRENT);
        }
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

    @Test
    void freePeriodPlannerRefreshesSameImmutableSnapshotWithoutPaidJobsOrOtherWeeks() {
        UUID store = seed(true);
        LocalDate start = LocalDate.parse("2026-09-14");
        queue.discover(store, NOW, 1);
        UUID snapshot = runner.prepareNext("synthetic-owner").snapshotId();
        assertThat(planner.evaluate(store, start, "UTC").outcome())
                .isEqualTo(SellerWeeklyV3PlanningResult.Outcome.UNCHANGED);
        var payload = jdbc.queryForObject("SELECT report_payload::text FROM weekly_review_snapshots WHERE id=?",
                String.class, snapshot);
        jdbc.update("UPDATE store_analytics_source_state SET revision=revision+1 WHERE store_id=?", store);
        var refreshed = planner.evaluate(store, start, "UTC");
        assertThat(refreshed.outcome()).isEqualTo(SellerWeeklyV3PlanningResult.Outcome.EVALUATED);
        assertThat(refreshed.review().state()).isEqualTo(SellerWeeklyV3ReadResult.State.CURRENT);
        assertThat(refreshed.review().snapshot()).get().extracting(PersistedWeeklyReviewV3Snapshot::id)
                .isEqualTo(snapshot);
        assertThat(jdbc.queryForObject("SELECT report_payload::text FROM weekly_review_snapshots WHERE id=?",
                String.class, snapshot)).isEqualTo(payload);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM weekly_review_snapshots WHERE store_id=?",
                Long.class, store)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM weekly_review_ai_jobs", Long.class)).isZero();
    }

    @Test
    void periodHttpReadIsAuthorizedValidatedAndNeverPreparesOrPays() throws Exception {
        UUID store = seed(true);
        String password = "Synthetic-" + UUID.randomUUID();
        String email = "historical-manager-" + UUID.randomUUID() + "@example.com";
        AppUser admin = new AppUser("historical-admin-" + UUID.randomUUID() + "@example.com",
                passwords.encode(password), "Synthetic administrator", UserRole.ADMIN);
        admin.changePassword(passwords.encode(password));
        admin = users.saveAndFlush(admin);
        AppUser manager = new AppUser(email, passwords.encode(password), "Synthetic manager", UserRole.MANAGER);
        manager.changePassword(passwords.encode(password));
        manager = users.saveAndFlush(manager);
        accesses.saveAndFlush(new UserStoreAccess(manager, stores.findById(store).orElseThrow(), admin));
        Cookie csrf = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn()
                .getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrf).isNotNull();
        var login = mvc.perform(post("/api/auth/login").cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON).content(JsonMapper.builder().build()
                        .writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) login.getRequest().getSession(false);
        String path = "/api/stores/" + store + "/weekly-reviews/seller-period";
        mvc.perform(get(path).param("periodStart", "2026-09-14")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/stores/{storeId}/weekly-reviews/seller-period", UUID.randomUUID())
                .session(session).param("periodStart", "2026-09-14")).andExpect(status().isForbidden());
        mvc.perform(get(path).session(session).param("periodStart", "2026-09-14"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.freshness").value("PREPARING"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM weekly_review_snapshots WHERE store_id=?",
                Long.class, store)).isZero();
        queue.discover(store, NOW, 1);
        UUID snapshot = runner.prepareNext("synthetic-owner").snapshotId();
        var payload = jdbc.queryForObject("SELECT report_payload::text FROM weekly_review_snapshots WHERE id=?",
                String.class, snapshot);
        mvc.perform(get(path).session(session).param("periodStart", "2026-09-14"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.freshness").value("CURRENT"))
                .andExpect(jsonPath("$.report.period.current.start").value("2026-09-14"))
                .andExpect(jsonPath("$.report.membership.basis").value(SellerWeeklyHistoricalMembership.BASIS));
        mvc.perform(get(path).session(session)).andExpect(status().isBadRequest());
        for (String invalid : List.of("not-a-date", "2026-09-15", "2026-10-05")) {
            mvc.perform(get(path).session(session).param("periodStart", invalid)).andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForObject("SELECT report_payload::text FROM weekly_review_snapshots WHERE id=?",
                String.class, snapshot)).isEqualTo(payload);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM weekly_review_snapshots WHERE store_id=?",
                Long.class, store)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM weekly_review_ai_jobs", Long.class)).isZero();
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
