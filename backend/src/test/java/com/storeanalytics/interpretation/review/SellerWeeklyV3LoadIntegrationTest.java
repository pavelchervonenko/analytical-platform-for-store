package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storeanalytics.metrics.repository.AttachRateRepository;
import com.storeanalytics.metrics.repository.AttachAttributionQualityRepository;
import com.storeanalytics.metrics.repository.SellerDocumentRepository;
import com.storeanalytics.metrics.service.SellerPeriodFacts;
import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.model.UserRole;
import com.storeanalytics.auth.model.UserStoreAccess;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.auth.repository.UserStoreAccessRepository;
import com.storeanalytics.store.repository.StoreRepository;
import jakarta.servlet.http.Cookie;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Synthetic volume checks, never a benchmark against business data or a production SLA. */
@SpringBootTest(properties = {"app.attach.attribution-enabled=true",
        "app.interpretation.weekly-review.enabled=true", "app.interpretation.seller-weekly-review.enabled=true"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class SellerWeeklyV3LoadIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-24T04:00:00Z");
    private static final String TIMEZONE = "Europe/Kaliningrad";
    private static final LocalDate PREVIOUS_START = LocalDate.of(2026, 8, 10);
    private static final LocalDate CURRENT_START = LocalDate.of(2026, 8, 17);

    @TestConfiguration
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock sellerLoadClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withCommand("postgres", "-c", "shared_preload_libraries=pg_stat_statements");

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private NamedParameterJdbcTemplate namedJdbc;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private PlatformTransactionManager transactions;
    @Autowired
    private SellerWeeklyReviewFactsSource factsSource;
    @Autowired
    private SellerWeeklyIdentityFactsSource identityFactsSource;
    @Autowired
    private SellerWeeklySourceIdentity sourceIdentity;
    @Autowired
    private SellerWeeklyV3PlanningService planner;
    @Autowired
    private SellerWeeklyV3BatchPlanningService batchPlanner;
    @Autowired
    private WeeklyReviewSnapshotStore snapshots;
    @Autowired
    private SellerWeeklySourceRevisionRepository revisions;
    @Autowired
    private AttachRateRepository storeAttach;
    @Autowired
    private AttachAttributionQualityRepository attributionQuality;
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
    private Fixture warrantyVolumeFixture;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET statement_timeout = '30s'");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
    }

    @BeforeEach
    void enableIsolatedQueryStatistics() {
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
    }

    @BeforeEach
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void provisionWarrantyVolumeBeforeMeasuringTheReader(TestInfo test) {
        if (!test.getTags().contains("seller-manual-warranty-volume")) {
            return;
        }
        // Provisioning validates all deferred events; it is not part of the reader's 3-minute budget.
        long started = System.nanoTime();
        boolean allFanout = test.getTags().contains("seller-manual-warranty-extreme-volume");
        warrantyVolumeFixture = fixture(130, 30, 10);
        addManualWarrantyAllocations(warrantyVolumeFixture, allFanout);
        analyzeFixtures();
        System.out.printf("seller-warranty-fixture synthetic decisions=320 allocations=%d provisionMs=%d%n",
                allFanout ? 2560 : 432, elapsedMillis(started));
    }

    @Test
    void authenticatedSellerApiUsesSameFactsAsOverviewAndRejectsUnauthorizedWrites() throws Exception {
        Fixture fixture = fixture(3, 1, 1);
        String password = "Synthetic-" + UUID.randomUUID();
        String managerEmail = "seller-manager-" + UUID.randomUUID() + "@example.com";
        String adminEmail = "seller-admin-" + UUID.randomUUID() + "@example.com";
        AppUser admin = new AppUser(adminEmail, passwords.encode(password), "Synthetic admin", UserRole.ADMIN);
        admin.changePassword(passwords.encode(password));
        admin = users.saveAndFlush(admin);
        AppUser manager = new AppUser(managerEmail, passwords.encode(password), "Synthetic manager", UserRole.MANAGER);
        manager.changePassword(passwords.encode(password));
        manager = users.saveAndFlush(manager);
        accesses.saveAndFlush(new UserStoreAccess(manager, stores.findById(fixture.storeId()).orElseThrow(), admin));
        MockHttpSession adminSession = authenticate(adminEmail, password);
        MockHttpSession managerSession = authenticate(managerEmail, password);
        Cookie managerCsrf = csrf(managerSession);
        Cookie adminCsrf = csrf(adminSession);
        String currentPath = "/api/stores/" + fixture.storeId() + "/weekly-reviews/seller-current";
        String generatePath = "/api/admin/seller-weekly-reviews/stores/" + fixture.storeId() + "/generate";
        mvc.perform(get(currentPath)).andExpect(status().isUnauthorized());
        mvc.perform(get(currentPath).session(managerSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.freshness").value("PREPARING"))
                .andExpect(jsonPath("$.report").doesNotExist());
        mvc.perform(post(generatePath).session(adminSession)).andExpect(status().isForbidden());
        mvc.perform(post(generatePath).session(managerSession).cookie(managerCsrf)
                .header("X-XSRF-TOKEN", managerCsrf.getValue())).andExpect(status().isForbidden());
        var generated = mvc.perform(post(generatePath).session(adminSession).cookie(adminCsrf)
                .header("X-XSRF-TOKEN", adminCsrf.getValue())).andExpect(status().isOk())
                .andExpect(jsonPath("$.freshness").value("CURRENT"))
                .andExpect(jsonPath("$.report.contractVersion").value(3))
                .andExpect(jsonPath("$.report.scope").value("SELLERS"))
                .andExpect(jsonPath("$.report.membership.selectedSellerCount").value(3)).andReturn();
        var mapper = JsonMapper.builder().build();
        JsonNode report = mapper.readTree(generated.getResponse().getContentAsString()).get("report");
        var overview = mvc.perform(get("/api/stores/{storeId}/overview-metrics", fixture.storeId())
                .session(managerSession).param("periodStart", CURRENT_START.toString())
                .param("periodEnd", CURRENT_START.plusDays(6).toString()).param("scope", "SELLERS"))
                .andExpect(status().isOk()).andReturn();
        JsonNode metrics = mapper.readTree(overview.getResponse().getContentAsString());
        assertThat(report.get("results").get(0).get("current").decimalValue())
                .isEqualByComparingTo(metrics.get("netRevenue").decimalValue());
        assertThat(report.get("additionalSales").get("revenue").get("current").decimalValue())
                .isEqualByComparingTo(metrics.get("additional").get("netRevenue").decimalValue());
        assertThat(report.get("membership").get("currentCohortHash"))
                .isEqualTo(report.get("membership").get("previousCohortHash"));
        var whole = mvc.perform(get("/api/stores/{storeId}/overview-metrics", fixture.storeId())
                .session(managerSession).param("periodStart", CURRENT_START.toString())
                .param("periodEnd", CURRENT_START.plusDays(6).toString()).param("scope", "STORE"))
                .andExpect(status().isOk()).andReturn();
        assertThat(mapper.readTree(whole.getResponse().getContentAsString()).get("netRevenue").decimalValue())
                .isNotEqualByComparingTo(metrics.get("netRevenue").decimalValue());
        mvc.perform(post(generatePath).session(adminSession).cookie(adminCsrf)
                .header("X-XSRF-TOKEN", adminCsrf.getValue())).andExpect(status().isOk())
                .andExpect(jsonPath("$.report.provenance.snapshotPublicId")
                        .value(report.get("provenance").get("snapshotPublicId").asText()));
        mvc.perform(get("/api/stores/{storeId}/weekly-reviews/current", fixture.storeId()).session(managerSession))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/stores/{storeId}/weekly-reviews/seller-current", UUID.randomUUID())
                .session(managerSession)).andExpect(status().isForbidden());
        jdbc.update("""
                UPDATE employee_store_assignments SET participates_in_ranking = false
                WHERE store_id = ? AND employee_id =
                    (SELECT employee_id FROM employee_store_assignments WHERE store_id = ?
                     AND participates_in_ranking = true ORDER BY employee_id LIMIT 1)
                """, fixture.storeId(), fixture.storeId());
        mvc.perform(get(currentPath).session(managerSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.freshness").value("STALE"));
        mvc.perform(post(generatePath).session(adminSession).cookie(adminCsrf)
                .header("X-XSRF-TOKEN", adminCsrf.getValue())).andExpect(status().isOk())
                .andExpect(jsonPath("$.freshness").value("CURRENT"))
                .andExpect(jsonPath("$.report.provenance.revision").value(2))
                .andExpect(jsonPath("$.report.membership.selectedSellerCount").value(2));
    }

    private MockHttpSession authenticate(String email, String password) throws Exception {
        Cookie token = csrf(null);
        var result = mvc.perform(post("/api/auth/login").cookie(token).header("X-XSRF-TOKEN", token.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JsonMapper.builder().build().writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private Cookie csrf(MockHttpSession session) throws Exception {
        var request = get("/api/auth/csrf");
        if (session != null) {
            request.session(session);
        }
        Cookie token = mvc.perform(request).andExpect(status().isOk()).andReturn()
                .getResponse().getCookie("XSRF-TOKEN");
        assertThat(token).isNotNull();
        return token;
    }

    @Test
    void orphanReturnIsScopedAndLateLinkCreatesReadyRevision() {
        Fixture fixture = fixture(1, 0, 1);
        var baseline = planner.evaluate(fixture.storeId()).review().snapshot().orElseThrow();
        assertThat(baseline.response().reportState()).isEqualTo(WeeklyReviewResponse.ReportState.READY);
        UUID originalId = jdbc.queryForObject("""
                SELECT id FROM sales_documents
                WHERE store_id = ? AND business_date BETWEEN ? AND ?
                  AND document_kind = 'SALE' ORDER BY id LIMIT 1
                """, UUID.class, fixture.storeId(), CURRENT_START, CURRENT_START.plusDays(6));
        UUID originalEmployeeId = jdbc.queryForObject(
                "SELECT employee_id FROM sales_documents WHERE id = ?", UUID.class, originalId);
        UUID originalItemId = jdbc.queryForObject(
                "SELECT id FROM sales_document_items WHERE sales_document_id = ? ORDER BY id LIMIT 1",
                UUID.class, originalId);
        UUID connectionId = jdbc.queryForObject(
                "SELECT connection_id FROM stores WHERE id = ?", UUID.class, fixture.storeId());
        UUID returnRunId = jdbc.queryForObject(
                "SELECT id FROM sync_runs WHERE store_id = ? AND sync_scope = 'RETURNS' LIMIT 1",
                UUID.class, fixture.storeId());
        UUID orphanId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sales_documents (id, connection_id, external_id, store_id,
                    document_kind, source_document_type, occurred_at, business_date,
                    net_amount, cost_amount, last_sync_run_id)
                VALUES (?, ?, ?, ?, 'RETURN', 'return', ?, ?, 10, 2, ?)
                """, orphanId, connectionId, orphanId.toString(), fixture.storeId(),
                Timestamp.from(Instant.parse("2026-08-20T12:00:00Z")), CURRENT_START.plusDays(3), returnRunId);
        jdbc.update("""
                INSERT INTO sales_document_items (sales_document_id, external_id, product_id,
                    product_name_snapshot, analytics_category_id, condition_type_snapshot,
                    quantity, unit_price, gross_amount, net_amount, cost_amount, cost_quality)
                SELECT ?, 'synthetic-orphan-item', item.product_id, item.product_name_snapshot,
                    item.analytics_category_id, item.condition_type_snapshot,
                    1, 10, 10, 10, 2, 'KNOWN'
                FROM sales_document_items item WHERE item.id = ?
                """, orphanId, originalItemId);

        var uncertain = planner.evaluate(fixture.storeId()).review().snapshot().orElseThrow();
        assertThat(uncertain.revision()).isEqualTo(baseline.revision() + 1);
        assertThat(uncertain.response().reportState()).isEqualTo(WeeklyReviewResponse.ReportState.PARTIAL);
        assertThat(uncertain.response().results().getFirst().current())
                .isEqualByComparingTo(baseline.response().results().getFirst().current());
        assertThat(uncertain.response().results().getFirst().metricState())
                .isEqualTo(WeeklyReviewResponse.MetricState.LIMITED);
        assertThat(uncertain.response().revenueDecomposition().salesRevenue().metricState())
                .isEqualTo(WeeklyReviewResponse.MetricState.READY);
        assertThat(uncertain.response().limitations()).anyMatch(item ->
                "RETURN_EMPLOYEE_MISSING".equals(item.code()) && item.affectedCount() == 1);

        jdbc.update("""
                UPDATE sales_documents SET original_document_id = ?, employee_id = ?,
                    attach_source_employee_external_id = (SELECT external_id FROM employees WHERE id = ?)
                WHERE id = ?
                """, originalId, originalEmployeeId, originalEmployeeId, orphanId);
        jdbc.update("""
                UPDATE sales_document_items SET original_item_id = ? WHERE sales_document_id = ?
                """, originalItemId, orphanId);
        var linked = planner.evaluate(fixture.storeId()).review().snapshot().orElseThrow();
        assertThat(linked.revision()).isEqualTo(uncertain.revision() + 1);
        assertThat(linked.response().reportState()).isEqualTo(WeeklyReviewResponse.ReportState.READY);
        assertThat(linked.response().limitations()).noneMatch(item ->
                "RETURN_EMPLOYEE_MISSING".equals(item.code()));
        assertThat(linked.response().results().getFirst().current())
                .isLessThan(uncertain.response().results().getFirst().current());

        jdbc.update("""
                UPDATE sales_documents SET attach_source_employee_external_id = 'not-imported'
                WHERE id = ?
                """, orphanId);
        var unknownAuthor = planner.evaluate(fixture.storeId()).review().snapshot().orElseThrow();
        assertThat(unknownAuthor.response().reportState()).isEqualTo(WeeklyReviewResponse.ReportState.PARTIAL);
        assertThat(unknownAuthor.response().limitations()).anyMatch(item ->
                "RETURN_EMPLOYEE_UNRESOLVED".equals(item.code()) && item.affectedCount() == 1);
        assertThat(unknownAuthor.response().limitations()).noneMatch(item ->
                "RETURN_EMPLOYEE_MISSING".equals(item.code()));
    }

    @Test
    void qualityReadRestoresCallerJitAndRollbackDoesNotLeakItToThePool() throws SQLException {
        Fixture fixture = fixture(3, 1, 1);
        // Flyway needs two startup connections. Reserve one so every measured call reuses the other.
        try (Connection reserved = dataSource.getConnection()) {
            assertThat(reserved.isClosed()).isFalse();
            Integer backend = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
            String initial = jdbc.queryForObject("SELECT current_setting('jit')", String.class);
            for (String callerJit : List.of("on", "off")) {
                new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    jdbc.execute("SET LOCAL jit = " + callerJit);
                    attributionQuality.read(fixture.storeId(), CURRENT_START, CURRENT_START.plusDays(6));
                    assertThat(jdbc.queryForObject("SELECT current_setting('jit')", String.class)).isEqualTo(callerJit);
                    String during = attributionQuality.readWith(fixture.storeId(), CURRENT_START,
                            CURRENT_START.plusDays(6), quality ->
                                    jdbc.queryForObject("SELECT current_setting('jit')", String.class));
                    assertThat(during).isEqualTo("off");
                    assertThat(jdbc.queryForObject("SELECT current_setting('jit')", String.class)).isEqualTo(callerJit);
                });
            }
            assertThat(jdbc.queryForObject("SELECT current_setting('jit')", String.class)).isEqualTo(initial);
            assertThatThrownBy(() -> attributionQuality.readWith(fixture.storeId(), CURRENT_START,
                    CURRENT_START.plusDays(6), quality -> {
                        throw new RuntimeException("Synthetic aggregate failure");
                    })).isExactlyInstanceOf(RuntimeException.class).hasMessage("Synthetic aggregate failure");
            assertThat(jdbc.queryForObject("SELECT current_setting('jit')", String.class)).isEqualTo(initial);
            assertThatThrownBy(() -> attributionQuality.read(fixture.storeId(), null, CURRENT_START.plusDays(6)))
                    .isInstanceOf(NullPointerException.class);
            assertThat(jdbc.queryForObject("SELECT current_setting('jit')", String.class)).isEqualTo(initial);
            assertThat(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)).isEqualTo(backend);
        }
    }

    @Test
    void profilesQualityOnlyReaderWithoutChangingItsFormulas() {
        Fixture small = fixture(3, 1, 1);
        analyzeFixtures();
        String query = (String) ReflectionTestUtils.getField(AttachAttributionQualityRepository.class, "QUERY");
        String previousJit = jdbc.queryForObject("SELECT current_setting('jit')", String.class);
        String plan = new TransactionTemplate(transactions).execute(status -> {
            jdbc.execute("SET LOCAL jit = off");
            return namedJdbc.queryForObject("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + query, Map.of(
                    "storeId", small.storeId(), "periodStart", CURRENT_START,
                    "periodEnd", CURRENT_START.plusDays(6)), String.class);
        });
        JsonNode root = JsonMapper.builder().build().readTree(plan).get(0);
        System.out.printf("seller-quality-only-plan synthetic executionMs=%.1f jitMs=%.1f jitFunctions=%d%n",
                root.path("Execution Time").asDouble(), root.path("JIT").path("Timing").path("Total").asDouble(),
                root.path("JIT").path("Functions").asLong());
        assertThat(root.path("Plan").path("Actual Rows").asLong()).isPositive();
        assertThat(root.has("JIT")).isFalse();
        assertThat(jdbc.queryForObject("SELECT current_setting('jit')", String.class)).isEqualTo(previousJit);
    }

    @Test
    void qualityOnlyCountsMatchLegacyStoreQualityForUnknownReturnsAndPendingWarranty() {
        Fixture fixture = fixture(3, 1, 5);
        // This scenario deliberately has no native return processor, unlike the normal load fixture.
        jdbc.update("""
                UPDATE sales_documents SET attach_source_employee_external_id = NULL
                WHERE store_id = ? AND document_kind = 'RETURN'
                """, fixture.storeId());
        jdbc.update("""
                INSERT INTO sales_document_items (sales_document_id, external_id, product_id,
                    product_name_snapshot, analytics_category_id, condition_type_snapshot,
                    quantity, unit_price, gross_amount, net_amount, cost_quality)
                SELECT document.id, 'pending-warranty', item.product_id, 'Synthetic warranty', category.id,
                    'NOT_APPLICABLE', 1, 100, 100, 100, 'MISSING'
                FROM sales_documents document
                JOIN sales_document_items item ON item.sales_document_id = document.id
                JOIN analytics_categories category ON category.code = 'WARRANTY_GENERIC'
                WHERE document.store_id = ? AND document.document_kind = 'RETURN'
                  AND document.business_date < ? LIMIT 1
                """, fixture.storeId(), CURRENT_START);
        var legacy = storeAttach.aggregate(fixture.storeId(), CURRENT_START, CURRENT_START.plusDays(6));
        var quality = attributionQuality.read(fixture.storeId(), CURRENT_START, CURRENT_START.plusDays(6));

        assertThat(quality).hasSameSizeAs(legacy);
        assertThat(quality).allSatisfy(value -> {
            var old = legacy.stream().filter(metric -> metric.metricCode().equals(value.metricCode()))
                    .findFirst().orElseThrow();
            assertThat(value.pendingWarrantyItemCount()).isEqualTo(old.ambiguousWarrantyItemCount());
            assertThat(value.unassignedReturnItemCount()).isEqualTo(old.unassignedReturnItemCount());
            assertThat(value.unassignedMetricReturnItemCount()).isEqualTo(old.unassignedMetricReturnItemCount());
            assertThat(value.preliminary()).isEqualTo(old.preliminary() || old.unassignedMetricReturnItemCount() > 0);
            assertThat(value.pendingWarrantyItemCount()).isOne();
            assertThat(value.unassignedReturnItemCount()).isPositive();
        });
        assertThat(quality).filteredOn(value -> "CASE_SAMSUNG".equals(value.metricCode())).singleElement()
                .satisfies(value -> assertThat(value.unassignedMetricReturnItemCount()).isEqualTo(4));
        assertThat(quality).filteredOn(value -> "GLASS_IPHONE".equals(value.metricCode())).singleElement()
                .satisfies(value -> assertThat(value.unassignedMetricReturnItemCount()).isZero());
        assertThat(quality).filteredOn(value -> "CHARGER_CABLE".equals(value.metricCode())).singleElement()
                .satisfies(value -> assertThat(value.unassignedMetricReturnItemCount()).isEqualTo(8));
    }

    @Test
    void fullReadsStayBoundedAndCardLimitPreservesAllSellerTotals() {
        Fixture small = fixture(3, 1, 1);
        Fixture large = fixture(130, 30, 20);
        analyzeFixtures();
        // Warm class initialization and both query shapes before observing query counts and durations.
        factsSource.load(small.storeId(), NOW, TIMEZONE);
        factsSource.load(large.storeId(), NOW, TIMEZONE);
        MeasuredFacts smallRead = measuredRead(small);
        MeasuredFacts largeRead = measuredRead(large);

        assertThat(smallRead.queries()).isPositive();
        assertThat(largeRead.queries()).isEqualTo(smallRead.queries());
        assertThat(largeRead.queries()).isLessThanOrEqualTo(24);
        assertFinancialFacts(largeRead.facts().comparison().current(), large);
        assertFinancialFacts(largeRead.facts().comparison().previous(), large);
        assertThat(largeRead.facts().sourceCoverage().completeBothWeeks()).isTrue();
        assertThat(largeRead.facts().sourceStability()).isEqualTo(SellerWeeklySourceStability.STABLE);
        assertThat(sourceIdentity.hash(identityFactsSource.load(large.storeId(), NOW, TIMEZONE)))
                .isEqualTo(sourceIdentity.hash(largeRead.facts()));

        long started = System.nanoTime();
        var generated = planner.evaluate(large.storeId());
        long generationMillis = elapsedMillis(started);
        assertThat(generated.outcome()).isEqualTo(SellerWeeklyV3PlanningResult.Outcome.EVALUATED);
        assertThat(generated.review().state()).isEqualTo(SellerWeeklyV3ReadResult.State.CURRENT);
        var response = generated.review().snapshot().orElseThrow().response();
        assertThat(response.membership().selectedSellerCount()).isEqualTo(130);
        assertThat(response.teamDisplay().totalCount()).isEqualTo(130);
        assertThat(response.employees()).hasSize(100);
        assertThat(response.teamDisplay().hiddenCount()).isEqualTo(30);
        assertThat(response.teamDisplay().hiddenCurrentNetRevenue())
                .isEqualByComparingTo(large.perSellerNet().multiply(BigDecimal.valueOf(30)));
        assertThat(response.teamDisplay().hiddenPreviousNetRevenue())
                .isEqualByComparingTo(response.teamDisplay().hiddenCurrentNetRevenue());
        assertThat(response.teamDisplay().hiddenCurrentAdditionalRevenue())
                .isEqualByComparingTo(large.perSellerAdditional().multiply(BigDecimal.valueOf(30)));
        assertThat(response.teamDisplay().hiddenPreviousAdditionalRevenue())
                .isEqualByComparingTo(response.teamDisplay().hiddenCurrentAdditionalRevenue());
        assertThat(response.additionalSales().revenue().current())
                .isEqualByComparingTo(large.perSellerAdditional().multiply(BigDecimal.valueOf(130)));
        assertUnchangedScans(large);
        explainDocumentReader(largeRead.facts());
        System.out.printf("seller-load synthetic sellers=3/130 documents=8/6400 items=24/19200 "
                        + "readQueries=%d/%d warmReadMs=%d/%d firstPlannerMs=%d%n",
                smallRead.queries(), largeRead.queries(), smallRead.millis(), largeRead.millis(), generationMillis);
    }

    @Test
    void identityMetadataKeepsOneMvccSnapshotAcrossConcurrentRosterCommit() {
        Fixture fixture = fixture(3, 1, 1);
        String original = sourceIdentity.hash(factsSource.load(fixture.storeId(), NOW, TIMEZONE));
        TransactionTemplate repeatableRead = new TransactionTemplate(transactions);
        repeatableRead.setReadOnly(true);
        repeatableRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try (var worker = Executors.newSingleThreadExecutor()) {
            repeatableRead.executeWithoutResult(transaction -> {
                var before = identityFactsSource.load(fixture.storeId(), NOW, TIMEZONE);
                assertThat(sourceIdentity.hash(before)).isEqualTo(original);
                var changed = worker.submit(() -> jdbc.update("""
                        UPDATE employee_store_assignments SET participates_in_ranking = false
                        WHERE store_id = ? AND employee_id = (
                            SELECT employee_id FROM employee_store_assignments
                            WHERE store_id = ? AND participates_in_ranking ORDER BY employee_id LIMIT 1)
                        """, fixture.storeId(), fixture.storeId()));
                try {
                    assertThat(changed.get(30, TimeUnit.SECONDS)).isOne();
                } catch (Exception exception) {
                    throw new IllegalStateException("Synthetic concurrent roster commit failed", exception);
                }
                var after = identityFactsSource.load(fixture.storeId(), NOW, TIMEZONE);
                assertThat(sourceIdentity.hash(after)).isEqualTo(original);
                assertThat(after.sourceRevision()).isEqualTo(before.sourceRevision());
            });
        }
        var committed = identityFactsSource.load(fixture.storeId(), NOW, TIMEZONE);
        assertThat(sourceIdentity.hash(committed)).isNotEqualTo(original);
        assertThat(committed.sourceRevision()).isEqualTo(2);
    }

    @Test
    void bulkChangesCoalescePerStoreAndNoOpUpdatesDoNotAdvanceRevision() {
        Fixture first = fixture(130, 30, 20);
        Fixture second = fixture(3, 1, 1);
        long firstRevision = revisions.read(first.storeId());
        long secondRevision = revisions.read(second.storeId());
        assertThat(firstRevision).isOne();
        assertThat(secondRevision).isOne();
        String update = """
                UPDATE sales_document_items item SET cost_amount = %s, version = item.version + 1
                FROM sales_documents document
                WHERE document.id = item.sales_document_id AND document.store_id IN (?, ?)
                """;
        BulkObservation noOp = explainBulk(update.formatted("item.cost_amount"), first, second);
        assertThat(revisions.read(first.storeId())).isEqualTo(firstRevision);
        assertThat(revisions.read(second.storeId())).isEqualTo(secondRevision);
        BulkObservation changed = explainBulk(update.formatted("item.cost_amount + 0.01"), first, second);
        assertThat(revisions.read(first.storeId())).isEqualTo(firstRevision + 1);
        assertThat(revisions.read(second.storeId())).isEqualTo(secondRevision + 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM store_analytics_source_events", Long.class)).isZero();
        assertThat(noOp.triggerCalls()).isEqualTo(19224);
        assertThat(changed.triggerCalls()).isEqualTo(noOp.triggerCalls());
        System.out.printf("seller-fence synthetic stores=2 items=19224 noOpCommitMs=%d changedCommitMs=%d "
                        + "noOpTriggerMs=%.1f changedTriggerMs=%.1f revisionIncrements=0/1 eventsRemaining=0%n",
                noOp.commitMillis(), changed.commitMillis(), noOp.triggerMillis(), changed.triggerMillis());
    }

    @Test
    void boundedMultiStoreBatchResumesWithoutDuplicatingSnapshotsOrRetainingPayloads() {
        // This database is isolated; never scan another test's old fixtures as current targets.
        jdbc.update("UPDATE stores SET is_active = false");
        List<Fixture> stores = List.of(fixture(3, 1, 1), fixture(3, 1, 1), fixture(130, 30, 20),
                fixture(3, 1, 1), fixture(0, 1, 1), fixture(3, 1, 1), fixture(3, 1, 1), fixture(3, 1, 1));
        Fixture incomplete = stores.getLast();
        jdbc.update("DELETE FROM sync_runs WHERE store_id = ? AND sync_scope = 'ORDERS'", incomplete.storeId());
        analyzeFixtures();
        var budget = new SellerWeeklyV3BatchPlanningService.Budget(3, 2, Duration.ofMinutes(2));
        UUID cursor = null;
        int scanned = 0;
        int evaluated = 0;
        int deferred = 0;
        long allocatedBefore = threadAllocatedBytes();
        long started = System.nanoTime();
        for (int pass = 0; pass < 3; pass++) {
            var batch = batchPlanner.scan(cursor, budget);
            assertThat(batch.scanned()).isEqualTo(pass < 2 ? 3 : 2);
            assertThat(batch.stopReason()).isEqualTo(pass < 2
                    ? SellerWeeklyV3BatchPlanningResult.StopReason.STORE_LIMIT
                    : SellerWeeklyV3BatchPlanningResult.StopReason.EXHAUSTED);
            assertThat(batch.unchanged()).isZero();
            scanned += batch.scanned();
            evaluated += batch.evaluated();
            deferred += batch.deferred();
            cursor = batch.afterStoreId();
        }
        long coldMillis = elapsedMillis(started);
        long coldAllocated = allocatedSince(allocatedBefore);
        assertThat(scanned).isEqualTo(8);
        assertThat(evaluated).isEqualTo(7);
        assertThat(deferred).isOne();
        List<UUID> ids = stores.stream().map(Fixture::storeId).toList();
        Long snapshotsBefore = snapshotCount(ids);
        assertThat(snapshotsBefore).isEqualTo(7);
        var checkpoints = namedJdbc.queryForList("""
                SELECT * FROM weekly_review_generation_state WHERE store_id IN (:ids) ORDER BY store_id
                """, Map.of("ids", ids));
        assertThat(checkpoints).hasSize(7);
        var period = new WeeklyReviewPolicyV1().period(NOW, TIMEZONE).current();
        var large = snapshots.findLatestV3(stores.get(2).storeId(), period).orElseThrow().response();
        assertThat(large.membership().selectedSellerCount()).isEqualTo(130);
        assertThat(large.employees()).hasSize(100);
        assertThat(large.teamDisplay().hiddenCount()).isEqualTo(30);
        assertThat(snapshots.findLatestV3(stores.get(4).storeId(), period).orElseThrow()
                .response().membership().selectedSellerCount()).isZero();

        allocatedBefore = threadAllocatedBytes();
        long before = readQueryCount();
        started = System.nanoTime();
        var warm = batchPlanner.scan(null,
                new SellerWeeklyV3BatchPlanningService.Budget(100, 2, Duration.ofMinutes(2)));
        long warmMillis = elapsedMillis(started);
        long warmQueries = readQueryCount() - before;
        long warmAllocated = allocatedSince(allocatedBefore);
        assertThat(warm.scanned()).isEqualTo(8);
        assertThat(warm.unchanged()).isEqualTo(7);
        assertThat(warm.deferred()).isOne();
        assertThat(warm.evaluated()).isZero();
        assertThat(warm.stopReason()).isEqualTo(SellerWeeklyV3BatchPlanningResult.StopReason.EXHAUSTED);
        assertThat(snapshotCount(ids)).isEqualTo(snapshotsBefore);
        assertThat(namedJdbc.queryForList("""
                SELECT * FROM weekly_review_generation_state WHERE store_id IN (:ids) ORDER BY store_id
                """, Map.of("ids", ids))).isEqualTo(checkpoints);
        System.out.printf("seller-batch synthetic stores=8 maxStores=3 pageSize=2 coldMs=%d warmMs=%d "
                        + "warmReadQueries=%d coldAllocatedBytes=%d warmAllocatedBytes=%d "
                        + "created=7 deferred=1 duplicateSnapshots=0%n",
                coldMillis, warmMillis, warmQueries, coldAllocated, warmAllocated);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status ->
                batchPlanner.scan(null, budget))).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void concurrentBulkCommitCannotPublishTheFinancialFactsReadBeforeItsCommit() throws Exception {
        Fixture fixture = fixture(130, 30, 20);
        analyzeFixtures();
        SellerWeeklyReviewFacts original = factsSource.load(fixture.storeId(), NOW, TIMEZONE);
        CountDownLatch updated = new CountDownLatch(1);
        CountDownLatch releaseCommit = new CountDownLatch(1);
        SellerWeeklyReviewFacts observed;
        try (var worker = Executors.newSingleThreadExecutor()) {
            var write = worker.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                int changed = jdbc.update("""
                        UPDATE sales_document_items item SET cost_amount = item.cost_amount + 0.01
                        FROM sales_documents document
                        WHERE document.id = item.sales_document_id AND document.store_id = ?
                        """, fixture.storeId());
                updated.countDown();
                try {
                    if (!releaseCommit.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Synthetic bulk commit was not released");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Synthetic bulk writer interrupted", exception);
                }
                return changed;
            }));
            try {
                assertThat(updated.await(30, TimeUnit.SECONDS)).isTrue();
                observed = factsSource.load(fixture.storeId(), NOW, TIMEZONE);
                assertThat(observed.sourceRevision()).isEqualTo(original.sourceRevision());
                assertThat(observed.comparison().current().metrics().totals()).isEqualTo(
                        original.comparison().current().metrics().totals());
                assertThat(observed.comparison().previous().metrics().totals()).isEqualTo(
                        original.comparison().previous().metrics().totals());
            } finally {
                releaseCommit.countDown();
            }
            assertThat(write.get(30, TimeUnit.SECONDS)).isEqualTo(19200);
        }
        assertThat(revisions.read(fixture.storeId())).isEqualTo(original.sourceRevision() + 1);
        assertThatThrownBy(() -> snapshots.persistV3Candidate(observed, NOW, sourceIdentity.hash(observed)))
                .isInstanceOf(SellerWeeklySourceChangedException.class);
        assertThat(snapshotCount(List.of(fixture.storeId()))).isZero();
        var generated = planner.evaluate(fixture.storeId());
        assertThat(generated.outcome()).isEqualTo(SellerWeeklyV3PlanningResult.Outcome.EVALUATED);
        assertThat(generated.review().state()).isEqualTo(SellerWeeklyV3ReadResult.State.CURRENT);
        SellerWeeklyReviewFacts committed = factsSource.load(fixture.storeId(), NOW, TIMEZONE);
        BigDecimal costIncrease = new BigDecimal("0.01").multiply(BigDecimal.valueOf(
                (fixture.documentsPerWeek() - 2L * fixture.returnCount()) * 3 * fixture.sellers()));
        assertThat(committed.comparison().current().metrics().totals().costAmount()).isEqualByComparingTo(
                original.comparison().current().metrics().totals().costAmount().add(costIncrease));
        assertThat(committed.comparison().previous().metrics().totals().costAmount()).isEqualByComparingTo(
                original.comparison().previous().metrics().totals().costAmount().add(costIncrease));
        assertThat(committed.comparison().current().metrics().totals().netRevenue()).isEqualByComparingTo(
                original.comparison().current().metrics().totals().netRevenue());
        var response = generated.review().snapshot().orElseThrow().response();
        assertThat(response.sourceIdentityHash()).isEqualTo(sourceIdentity.hash(committed));
        assertThat(response.results()).filteredOn(metric -> "GROSS_PROFIT".equals(metric.code()))
                .singleElement().satisfies(metric -> {
                    assertThat(metric.current()).isEqualByComparingTo(
                            committed.comparison().current().metrics().totals().grossProfit());
                    assertThat(metric.previous()).isEqualByComparingTo(
                            committed.comparison().previous().metrics().totals().grossProfit());
                });
        assertThat(snapshotCount(List.of(fixture.storeId()))).isOne();
        System.out.println("seller-bulk-race synthetic sellers=130 items=19200 revisionIncrements=1 "
                + "staleCandidatesRejected=1 freshSnapshots=1 state=CURRENT");
    }

    @Test
    @Tag("seller-manual-warranty-volume")
    void heavyManualWarrantyAllocationsPreserveSellerQuantitiesAndFinancialReconciliation() {
        assertManualWarrantyVolume(false);
    }

    @Test
    @Tag("seller-manual-warranty-volume")
    @Tag("seller-manual-warranty-extreme-volume")
    void extremeManualWarrantyFanoutPreservesSellerQuantitiesAndFinancialReconciliation() {
        assertManualWarrantyVolume(true);
    }

    private void assertManualWarrantyVolume(boolean allFanout) {
        Fixture fixture = java.util.Objects.requireNonNull(warrantyVolumeFixture);
        long allocatedBefore = threadAllocatedBytes();
        MeasuredFacts read = measuredRead(fixture);
        long allocated = allocatedSince(allocatedBefore);
        assertThat(read.queries()).isLessThanOrEqualTo(24);
        assertThat(read.facts().sourceRevision()).isEqualTo(2);
        BigDecimal warrantyQuantity = BigDecimal.valueOf(allFanout
                ? fixture.sellers() * 8L : 8L * 8 + (fixture.sellers() - 8));
        BigDecimal warrantyRevenue = warrantyQuantity.multiply(BigDecimal.TEN);
        var comparison = read.facts().comparison();
        for (SellerPeriodFacts period : List.of(comparison.current(), comparison.previous())) {
            assertThat(period.metrics().totals().netRevenue()).isEqualByComparingTo(
                    fixture.perSellerNet().multiply(BigDecimal.valueOf(fixture.sellers())).add(warrantyRevenue));
            assertThat(period.attachRates()).filteredOn(metric -> "WARRANTY_GENERIC_NEW".equals(metric.metricCode()))
                    .singleElement().satisfies(metric -> {
                        assertThat(metric.numeratorReceiptCount()).isEqualByComparingTo(warrantyQuantity);
                        assertThat(metric.denominatorReceiptCount()).isEqualByComparingTo("780");
                        assertThat(metric.ambiguousWarrantyItemCount()).isZero();
                        assertThat(metric.preliminary()).isFalse();
                    });
        }
        var result = planner.evaluate(fixture.storeId());
        assertThat(result.outcome()).isEqualTo(SellerWeeklyV3PlanningResult.Outcome.EVALUATED);
        assertThat(result.review().state()).isEqualTo(SellerWeeklyV3ReadResult.State.CURRENT);
        var response = result.review().snapshot().orElseThrow().response();
        assertThat(response.employees()).hasSize(100);
        assertThat(response.teamDisplay().hiddenCount()).isEqualTo(30);
        var displayed = response.employees().stream()
                .map(employee -> UUID.fromString(employee.card().employeePublicId())).toList();
        BigDecimal hiddenRevenue = comparison.current().metrics().employees().stream()
                .filter(employee -> !displayed.contains(employee.employeeId()))
                .map(employee -> employee.netRevenue()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(response.teamDisplay().hiddenCurrentNetRevenue()).isEqualByComparingTo(
                hiddenRevenue);
        assertThat(response.teamDisplay().hiddenPreviousNetRevenue()).isEqualByComparingTo(hiddenRevenue);
        System.out.printf("seller-warranty-load synthetic sellers=130 outsideSellers=30 decisions=320 "
                        + "allocations=%d fanoutDecisions=%d readQueries=%d readMs=%d "
                        + "allocatedBytes=%d state=CURRENT%n",
                allFanout ? 2560 : 432, allFanout ? 320 : 16, read.queries(), read.millis(), allocated);
    }

    private void addManualWarrantyAllocations(Fixture fixture, boolean allFanout) {
        UUID actor = UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            // Fixture population uses the same published fingerprint views; no global/session JIT changes.
            jdbc.execute("SET LOCAL jit = off");
            jdbc.update("""
                    INSERT INTO app_users (id, email, password_hash, display_name, role, password_change_required)
                    VALUES (?, ?, 'not-a-login-hash', 'Synthetic manager', 'ADMIN', false)
                    """, actor, actor + "@example.com");
            jdbc.update("""
                    WITH fanout_sellers AS (
                        SELECT employee_id FROM employee_store_assignments
                        WHERE store_id = ? AND (? OR participates_in_ranking) ORDER BY employee_id LIMIT ?
                    )
                    INSERT INTO sales_document_items (sales_document_id, external_id, product_id,
                        product_name_snapshot, analytics_category_id, condition_type_snapshot,
                        quantity, unit_price, gross_amount, net_amount, cost_amount, cost_quality)
                    SELECT document.id, 'manual-warranty', device.product_id, 'Synthetic warranty', category.id,
                        'NOT_APPLICABLE', allocation.quantity, 10, allocation.quantity * 10,
                        allocation.quantity * 10, allocation.quantity, 'KNOWN'
                    FROM sales_documents document
                    JOIN sales_document_items device ON device.sales_document_id = document.id
                    JOIN analytics_categories device_category ON device_category.id = device.analytics_category_id
                    CROSS JOIN analytics_categories category
                    CROSS JOIN LATERAL (
                        SELECT CASE WHEN document.employee_id IN (SELECT employee_id FROM fanout_sellers)
                            THEN ? ELSE 1 END AS quantity
                    ) allocation
                    WHERE document.store_id = ? AND document.document_kind = 'SALE'
                      AND split_part(document.external_id, ':', 3)::integer = ?
                      AND device_category.code = 'SAMSUNG_NEW' AND category.code = 'WARRANTY_GENERIC'
                    """, fixture.storeId(), allFanout, allFanout ? 160 : 8,
                    fixture.documentsPerWeek() - fixture.returnCount(), fixture.storeId(),
                    fixture.documentsPerWeek() % 5 == 0
                            ? fixture.documentsPerWeek() - 1 : fixture.documentsPerWeek());
            jdbc.update("""
                    UPDATE sales_documents document SET net_amount = document.net_amount + warranty.net_amount,
                        cost_amount = document.cost_amount + warranty.cost_amount
                    FROM sales_document_items warranty
                    WHERE document.id = warranty.sales_document_id AND document.store_id = ?
                      AND warranty.external_id = 'manual-warranty'
                    """, fixture.storeId());
            assertThat(jdbc.update("""
                    INSERT INTO warranty_attach_decisions
                        (id, source_item_id, revision, action, source_fingerprint, actor_id, reason)
                    SELECT gen_random_uuid(), source.id, 1, 'ALLOCATE', source.source_fingerprint, ?,
                        'Synthetic multi-device allocation'
                    FROM warranty_attach_sources source WHERE source.store_id = ?
                    """, actor, fixture.storeId())).isEqualTo(320);
            assertThat(jdbc.update("""
                    INSERT INTO warranty_attach_allocations
                        (decision_id, device_item_id, quantity, target_fingerprint,
                         device_document_id, device_type, business_date, employee_id)
                    SELECT decision.id, target.id, 1,
                        md5(concat_ws(':', target.fingerprint, context.fingerprint)), target.document_id,
                        target.device_type, target.business_date, target.employee_id
                    FROM warranty_attach_items source
                    JOIN warranty_attach_latest_decisions decision ON decision.source_item_id = source.id
                    JOIN warranty_attach_items target ON target.store_id = source.store_id
                        AND target.employee_id = source.employee_id AND target.document_kind = 'SALE'
                        AND target.device_type = 'NEW'
                        AND date_trunc('week', target.business_date::timestamp)
                            = date_trunc('week', source.business_date::timestamp)
                    JOIN LATERAL (
                        SELECT fingerprint FROM warranty_attach_document_context
                        WHERE document_id = target.document_id
                    ) context ON true
                    WHERE source.store_id = ? AND decision.action = 'ALLOCATE'
                      AND (source.quantity > 1 OR split_part(target.document_external_id, ':', 3)::integer = 1)
                    """, fixture.storeId())).isEqualTo(allFanout ? 2560 : 432);
            // Autovacuum cannot analyze uncommitted fixtures; deferred validators need realistic plans too.
            analyzeFixtures();
            jdbc.execute("ANALYZE warranty_attach_decisions");
            jdbc.execute("ANALYZE warranty_attach_allocations");
            // Commit validates all source/target fingerprints and complete quantities with real constraints.
        });
    }

    private Long snapshotCount(List<UUID> ids) {
        return namedJdbc.queryForObject("SELECT count(*) FROM weekly_review_snapshots WHERE store_id IN (:ids)",
                Map.of("ids", ids), Long.class);
    }

    private long threadAllocatedBytes() {
        var bean = ManagementFactory.getThreadMXBean();
        if (bean instanceof com.sun.management.ThreadMXBean allocation
                && allocation.isThreadAllocatedMemorySupported() && allocation.isThreadAllocatedMemoryEnabled()) {
            return allocation.getThreadAllocatedBytes(Thread.currentThread().threadId());
        }
        return -1;
    }

    private long allocatedSince(long before) {
        long after = threadAllocatedBytes();
        return before < 0 || after < 0 ? -1 : after - before;
    }

    private MeasuredFacts measuredRead(Fixture fixture) {
        long before = readQueryCount();
        long started = System.nanoTime();
        SellerWeeklyReviewFacts facts = factsSource.load(fixture.storeId(), NOW, TIMEZONE);
        long millis = elapsedMillis(started);
        return new MeasuredFacts(facts, readQueryCount() - before, millis);
    }

    private long readQueryCount() {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(calls), 0)::bigint FROM pg_stat_statements
                WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
                  AND query ~* '^\\s*(SELECT|WITH|SET LOCAL)\\s'
                  AND query !~* '^\\s*SELECT\\s+\\$1\\s*$'
                  AND query NOT ILIKE '%pg_stat_statements%'
                """, Long.class);
    }

    private void assertFinancialFacts(SellerPeriodFacts facts, Fixture fixture) {
        assertThat(facts.attachFormulaVersion()).isEqualTo("attach-rate-v4");
        assertThat(facts.metrics().cohort().employeeIds()).hasSize(fixture.sellers());
        assertThat(facts.metrics().employees()).hasSize(fixture.sellers());
        assertThat(facts.documents()).hasSize(fixture.sellers());
        assertThat(facts.metrics().totals().netRevenue()).isEqualByComparingTo(
                fixture.perSellerNet().multiply(BigDecimal.valueOf(fixture.sellers())));
        assertThat(facts.documents()).allSatisfy(document -> {
            assertThat(document.saleDocumentCount()).isEqualTo(fixture.documentsPerWeek() - fixture.returnCount());
            assertThat(document.returnDocumentCount()).isEqualTo(fixture.returnCount());
        });
    }

    private void assertUnchangedScans(Fixture fixture) {
        var period = new WeeklyReviewPolicyV1().period(NOW, TIMEZONE).current();
        var checkpoint = snapshots.findV3GenerationState(fixture.storeId(), period).orElseThrow();
        for (int scan = 0; scan < 3; scan++) {
            long before = readQueryCount();
            long started = System.nanoTime();
            var unchanged = planner.evaluate(fixture.storeId());
            long millis = elapsedMillis(started);
            long queries = readQueryCount() - before;
            assertThat(unchanged.outcome()).isEqualTo(SellerWeeklyV3PlanningResult.Outcome.UNCHANGED);
            assertThat(unchanged.review().state()).isEqualTo(SellerWeeklyV3ReadResult.State.CURRENT);
            assertThat(queries).isLessThanOrEqualTo(10);
            assertThat(snapshots.findV3GenerationState(fixture.storeId(), period)).contains(checkpoint);
            System.out.printf("seller-planner synthetic unchangedScan=%d readQueries=%d elapsedMs=%d%n",
                    scan + 1, queries, millis);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM weekly_review_snapshots WHERE store_id = ?",
                Long.class, fixture.storeId())).isOne();
    }

    private void explainDocumentReader(SellerWeeklyReviewFacts facts) {
        String query = (String) ReflectionTestUtils.getField(SellerDocumentRepository.class, "QUERY");
        assertThat(query).isNotBlank();
        String plan = namedJdbc.queryForObject("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + query, Map.of(
                "storeId", facts.storeId(),
                "employeeIds", facts.comparison().current().metrics().cohort().employeeIds(),
                "periodStart", CURRENT_START, "periodEnd", CURRENT_START.plusDays(6)), String.class);
        JsonNode root = JsonMapper.builder().build().readTree(plan).get(0);
        assertThat(root.path("Plan").path("Actual Rows").asLong()).isEqualTo(130);
        assertThat(root.path("Plan").path("Actual Loops").asLong()).isOne();
        System.out.printf("seller-document-plan synthetic resultRows=130 executionMs=%.1f "
                        + "sharedHits=%d sharedReads=%d%n",
                root.path("Execution Time").asDouble(), root.path("Plan").path("Shared Hit Blocks").asLong(),
                root.path("Plan").path("Shared Read Blocks").asLong());
    }

    private BulkObservation explainBulk(String update, Fixture first, Fixture second) {
        long started = System.nanoTime();
        String plan = new TransactionTemplate(transactions).execute(status -> jdbc.queryForObject(
                "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + update,
                String.class, first.storeId(), second.storeId()));
        long commitMillis = elapsedMillis(started);
        JsonNode triggers = JsonMapper.builder().build().readTree(plan).get(0).path("Triggers");
        for (JsonNode trigger : triggers) {
            if ("seller_source_sales_items".equals(trigger.path("Trigger Name").asText())) {
                return new BulkObservation(commitMillis, trigger.path("Time").asDouble(),
                        trigger.path("Calls").asLong());
            }
        }
        throw new AssertionError("EXPLAIN did not measure the seller item trigger");
    }

    private Fixture fixture(int sellers, int outsideSellers, int documentsPerWeek) {
        UUID connection = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        UUID saleRun = UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO integration_connections (id, connection_key, source_system, display_name)
                    VALUES (?, ?, 'LIVESKLAD', 'Synthetic connection')
                    """, connection, connection.toString());
            jdbc.update("""
                    INSERT INTO stores (id, connection_id, external_id, name, timezone)
                    VALUES (?, ?, ?, 'Synthetic seller load', ?)
                    """, storeId, connection, storeId.toString(), TIMEZONE);
            for (String scope : List.of("SALES", "RETURNS", "ORDERS")) {
                jdbc.update("""
                        INSERT INTO sync_runs (id, connection_id, store_id, source_system, trigger_type,
                            sync_scope, status, period_start, period_end, started_at, finished_at)
                        VALUES (?, ?, ?, 'LIVESKLAD', 'MANUAL', ?, 'SUCCESS', ?, ?, ?, ?)
                        """, "SALES".equals(scope) ? saleRun : UUID.randomUUID(), connection, storeId, scope,
                        Timestamp.from(Instant.parse("2026-08-09T22:00:00Z")),
                        Timestamp.from(Instant.parse("2026-08-23T22:00:00Z")),
                        Timestamp.from(NOW.minusSeconds(7200)), Timestamp.from(NOW.minusSeconds(3600)));
            }
            jdbc.update("""
                    INSERT INTO products (id, connection_id, external_id, name, source_kind)
                    VALUES (?, ?, ?, 'Synthetic product', 'PRODUCT')
                    """, product, connection, product.toString());
            jdbc.update("""
                    WITH inserted AS (
                        INSERT INTO employees (connection_id, external_id, full_name)
                        SELECT ?, ?::text || ':' || ordinal, 'Synthetic seller ' || ordinal
                        FROM generate_series(1, ?) ordinal RETURNING id, external_id
                    ) INSERT INTO employee_store_assignments (employee_id, store_id, participates_in_ranking)
                    SELECT id, ?, split_part(external_id, ':', 2)::integer <= ? FROM inserted
                    """, connection, storeId.toString(), sellers + outsideSellers, storeId, sellers);
            insertDocuments(connection, storeId, saleRun, documentsPerWeek);
            insertItems(storeId, product);
        });
        return new Fixture(storeId, sellers, documentsPerWeek);
    }

    private void insertDocuments(UUID connection, UUID storeId, UUID saleRun, int documentsPerWeek) {
        jdbc.update("""
                INSERT INTO sales_documents (connection_id, external_id, store_id, employee_id,
                    document_kind, source_document_type, occurred_at, business_date, net_amount,
                    cost_amount, last_sync_run_id)
                SELECT ?, assignment.employee_id::text || ':' || week || ':' || ordinal, ?, assignment.employee_id,
                    'SALE', 'sale', ?::date + (week * 7 + LEAST((ordinal - 1) / 3, 6)),
                    ?::date + (week * 7 + LEAST((ordinal - 1) / 3, 6)), 115, 52, ?
                FROM employee_store_assignments assignment
                CROSS JOIN generate_series(0, 1) week CROSS JOIN generate_series(1, ?) ordinal
                WHERE assignment.store_id = ?
                """, connection, storeId, PREVIOUS_START, PREVIOUS_START, saleRun, documentsPerWeek, storeId);
        jdbc.update("""
                UPDATE sales_documents returned SET document_kind = 'RETURN', source_document_type = 'return',
                    original_document_id = original.id,
                    attach_source_employee_external_id = processor.external_id
                FROM sales_documents original, employees processor
                WHERE returned.store_id = ? AND original.store_id = returned.store_id
                  AND processor.id = returned.employee_id AND processor.connection_id = returned.connection_id
                  AND split_part(returned.external_id, ':', 3)::integer % 5 = 0
                  AND original.external_id = returned.employee_id::text || ':'
                      || split_part(returned.external_id, ':', 2) || ':'
                      || (split_part(returned.external_id, ':', 3)::integer - 1)
                """, storeId);
        // A native LiveSklad return has an explicit processor fact; payroll employee_id is not its substitute.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sales_documents returned
                LEFT JOIN employees processor ON processor.connection_id = returned.connection_id
                    AND processor.external_id = returned.attach_source_employee_external_id
                    AND processor.source_system = 'LIVESKLAD'
                WHERE returned.store_id = ? AND returned.document_kind = 'RETURN'
                  AND processor.id IS DISTINCT FROM returned.employee_id
                """, Long.class, storeId)).isZero();
    }

    private void insertItems(UUID storeId, UUID product) {
        jdbc.update("""
                INSERT INTO sales_document_items (sales_document_id, external_id, product_id,
                    product_name_snapshot, analytics_category_id, condition_type_snapshot, quantity,
                    unit_price, gross_amount, net_amount, cost_amount, cost_quality)
                SELECT document.id, category.code, ?, 'Synthetic ' || category.code, category.id,
                    CASE category.code WHEN 'SAMSUNG_NEW' THEN 'NEW' ELSE 'NOT_APPLICABLE' END,
                    1, sample.amount, sample.amount, sample.amount, sample.cost,
                    CASE WHEN sample.cost = 0 THEN 'ZERO_SERVICE' ELSE 'KNOWN' END
                FROM sales_documents document
                CROSS JOIN (VALUES ('SAMSUNG_NEW', 100, 50), ('CHARGER_CABLE', 10, 2),
                    ('SETUP_SERVICE', 5, 0)) sample(code, amount, cost)
                JOIN analytics_categories category ON category.code = sample.code
                WHERE document.store_id = ?
                """, product, storeId);
        jdbc.update("""
                UPDATE sales_document_items returned SET original_item_id = original.id
                FROM sales_documents document, sales_document_items original
                WHERE document.id = returned.sales_document_id AND document.store_id = ?
                  AND document.document_kind = 'RETURN' AND original.sales_document_id = document.original_document_id
                  AND original.analytics_category_id = returned.analytics_category_id
                """, storeId);
    }

    private void analyzeFixtures() {
        for (String table : List.of("sales_documents", "sales_document_items", "employee_store_assignments")) {
            jdbc.execute("ANALYZE " + table);
        }
    }

    private static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private record Fixture(UUID storeId, int sellers, int documentsPerWeek) {
        int returnCount() {
            return documentsPerWeek / 5;
        }

        BigDecimal perSellerNet() {
            return BigDecimal.valueOf((documentsPerWeek - 2L * returnCount()) * 115);
        }

        BigDecimal perSellerAdditional() {
            return BigDecimal.valueOf((documentsPerWeek - 2L * returnCount()) * 15);
        }
    }

    private record MeasuredFacts(SellerWeeklyReviewFacts facts, long queries, long millis) { }

    private record BulkObservation(long commitMillis, double triggerMillis, long triggerCalls) { }
}
