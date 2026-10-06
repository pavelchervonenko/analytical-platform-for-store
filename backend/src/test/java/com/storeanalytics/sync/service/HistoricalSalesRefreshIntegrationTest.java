package com.storeanalytics.sync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.model.UserRole;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.integration.connection.model.IntegrationConnection;
import com.storeanalytics.integration.connection.repository.IntegrationConnectionRepository;
import com.storeanalytics.integration.livesklad.client.HistoricalSalesReadScope;
import com.storeanalytics.integration.livesklad.client.LiveSkladClient;
import com.storeanalytics.integration.livesklad.dto.LiveSkladSaleDetailPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladSalePositionPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladSaleSummaryPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladCashItemPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladCashRegisterPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladCashTransactionPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladReturnDetailPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladReturnPositionPayload;
import com.storeanalytics.integration.livesklad.exception.LiveSkladException;
import com.storeanalytics.sync.exception.HistoricalSalesReadBudgetException;
import com.storeanalytics.sync.exception.HistoricalSalesDependencyException;
import com.storeanalytics.sync.exception.SalesSyncException;
import com.storeanalytics.sync.model.SyncJob;
import com.storeanalytics.sync.model.SyncJobDefinition;
import com.storeanalytics.sync.model.SyncJobStatus;
import com.storeanalytics.sync.model.SyncJobType;
import com.storeanalytics.sync.model.SyncTriggerType;
import com.storeanalytics.sync.repository.HistoricalSalesRefreshStore;
import com.storeanalytics.sync.repository.SyncJobRepository;
import java.sql.Timestamp;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"app.sync.worker-enabled=false", "app.sync.historical-sales.initial-delay=1d",
        "app.sync.historical-sales.enabled=true", "app.sync.historical-sales.start-date=2026-10-01"})
@Testcontainers(disabledWithoutDocker = true)
class HistoricalSalesRefreshIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-06T06:00:00Z");
    private static final Instant START = Instant.parse("2026-09-30T22:00:00Z");
    private static final String WORKER = "historical-fixture-worker";
    private static final ZoneId ZONE = ZoneId.of("Europe/Kaliningrad");
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    @Autowired private HistoricalSalesRefreshService historical;
    @Autowired private HistoricalSalesRefreshStore states;
    @Autowired private SyncJobService routine;
    @Autowired private SyncJobCoordinator coordinator;
    @Autowired private SyncJobExecutionService execution;
    @Autowired private SalesSyncService sales;
    @Autowired private ReturnSyncService returns;
    @Autowired private SyncJobRepository jobs;
    @Autowired private IntegrationConnectionRepository connections;
    @Autowired private AppUserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private PlatformTransactionManager transactions;
    @MockitoBean private LiveSkladClient source;
    @MockitoSpyBean private HistoricalSalesDependencyGuard dependencies;
    private IntegrationConnection connection;
    private AppUser admin;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void fixtures() {
        // Keep stores/employees and their immutable membership baseline intact even in this local fixture.
        for (String table : List.of("historical_sales_refresh_state", "sync_run_errors", "data_quality_issues",
                "sales_payments", "sales_document_items", "sales_documents", "raw_record_versions",
                "sync_runs", "sync_jobs", "product_category_assignments")) {
            jdbc.update("DELETE FROM " + table);
        }
        clock.set(NOW);
        connection = connections.findByConnectionKeyAndActiveTrue("livesklad-default").orElseThrow();
        admin = users.findByEmailIgnoreCase("historical@example.invalid").orElseGet(() ->
                users.saveAndFlush(new AppUser("historical@example.invalid", "{noop}fixture",
                        "Historical Fixture", UserRole.ADMIN)));
        UUID product = jdbc.query("SELECT id FROM products WHERE external_id = 'historical-product'",
                (row, index) -> row.getObject("id", UUID.class)).stream().findFirst().orElseGet(UUID::randomUUID);
        jdbc.update("""
                INSERT INTO products (id, connection_id, source_system, external_id, code, name, source_kind)
                VALUES (?, ?, 'LIVESKLAD', 'historical-product', 'historical-product', 'Fixture', 'UNKNOWN')
                ON CONFLICT (id) DO UPDATE SET name = 'Fixture', source_kind = 'UNKNOWN', source_updated_at = NULL
                """, product, connection.getId());
        jdbc.update("""
                INSERT INTO product_category_assignments
                (product_id, analytics_category_id, condition_type, assignment_source, rule_version,
                 valid_from, assigned_by, change_reason)
                SELECT ?, id, 'NOT_APPLICABLE', 'INITIAL_IMPORT', 'fixture', '2026-01-01', ?, 'Fixture'
                FROM analytics_categories WHERE code = 'CHARGER_CABLE'
                """, product, admin.getId());
        for (String store : List.of("historical-store-a", "historical-store-b")) {
            jdbc.update("""
                    INSERT INTO stores (connection_id, source_system, external_id, name, timezone,
                        business_day_start, opens_at, closes_at)
                    VALUES (?, 'LIVESKLAD', ?, 'Fixture', 'Europe/Kaliningrad', '00:00', '10:00', '21:00')
                    ON CONFLICT (connection_id, external_id)
                        WHERE connection_id IS NOT NULL AND external_id IS NOT NULL DO NOTHING
                    """, connection.getId(), store);
        }
        when(source.fetchSales(anyString(), any(), any())).thenReturn(List.of());
    }

    @Test
    void routineSuccessGatesHistoricalSelectionAndCommitSurvivesReinitialization() {
        assertThat(historical.enqueue()).isEmpty();
        assertThat(states.find(connection.getId(), false)).isEmpty();
        successfulJob(SyncJobType.INCREMENTAL, null, routineStart(), routineEnd());
        SyncJobView job = historical.enqueue().orElseThrow();
        assertThat(job.periodStart()).isEqualTo(START);
        assertThat(job.periodStart()).isBefore(routineStart());
        assertThat(states.find(connection.getId(), false).orElseThrow().cursorStart()).isEqualTo(START);
        SyncJobClaim claim = coordinator.claimNext(WORKER).orElseThrow();
        execution.execute(claim);
        verify(source).fetchSales("historical-store-a", job.periodStart(), job.periodEnd());
        verify(source).fetchSales("historical-store-b", job.periodStart(), job.periodEnd());
        coordinator.completeStep(job.id(), WORKER);
        assertThat(routine.get(job.id()).status()).isEqualTo(SyncJobStatus.SUCCESS);
        states.initialize(connection.getId(), START, routineStart(), 180, LocalDate.of(2026, 10, 6), clock.instant());
        assertThat(new HistoricalSalesRefreshStore(jdbc).find(connection.getId(), false).orElseThrow().cursorStart())
                .isEqualTo(job.periodEnd());
    }

    @Test
    void fullCycleRepeatsExplicitStartAndNeverSilentlyIncludesOlderLedger() {
        successfulJob(SyncJobType.INCREMENTAL, null, routineStart(), routineEnd());
        for (int i = 0; i < 16; i++) {
            var job = historical.enqueue().orElseThrow();
            assertThat(job.periodStart()).isAfterOrEqualTo(START);
            var claim = coordinator.claimNext(WORKER).orElseThrow();
            execution.execute(claim);
            coordinator.completeStep(job.id(), WORKER);
        }
        var state = states.find(connection.getId(), false).orElseThrow();
        assertThat(state.cursorStart()).isEqualTo(routineStart());
        assertThat(state.lastCycleCompletedAt()).isEqualTo(NOW);
        assertThat(historical.enqueue()).isEmpty();
        clock.set(NOW.plus(Duration.ofDays(1)));
        successfulJob(SyncJobType.INCREMENTAL, null, routineStart(), routineEnd());
        assertThat(historical.enqueue().orElseThrow().periodStart()).isEqualTo(START);
    }

    @Test
    void permanentFailureBlocksUntilNewSuccessfulAuditedBackfillCoversThatWindow() {
        successfulJob(SyncJobType.INCREMENTAL, null, routineStart(), routineEnd());
        var job = historical.enqueue().orElseThrow();
        coordinator.claimNext(WORKER).orElseThrow();
        coordinator.retryOrFail(job.id(), WORKER, "permanent fixture failure", false, Duration.ZERO);
        jdbc.update("DELETE FROM sync_jobs WHERE id = ?", job.id());
        assertThat(historical.enqueue()).isEmpty();
        assertThat(states.find(connection.getId(), false).orElseThrow().cursorStart()).isEqualTo(START);
        assertThat(states.find(connection.getId(), false).orElseThrow().blockedAt()).isEqualTo(NOW);
        clock.set(NOW.plusSeconds(1));
        successfulJob(SyncJobType.BACKFILL, admin, START, START.plus(Duration.ofDays(1)));
        assertThat(historical.enqueue().orElseThrow().periodStart()).isEqualTo(job.periodEnd());
    }

    @Test
    void dailyAttemptBudgetPersistsAndResetsOnlyWithNextBusinessDay() {
        successfulJob(SyncJobType.INCREMENTAL, null, routineStart(), routineEnd());
        var job = historical.enqueue().orElseThrow();
        var claim = coordinator.claimNext(WORKER).orElseThrow();
        var scope = new HistoricalSalesReadScope.Context(job.id(), WORKER, claim.attemptCount(),
                claim.windowStart(), claim.windowEnd());
        for (int i = 0; i < 200; i++) {
            historical.chargeRequest(scope);
        }
        assertThatThrownBy(() -> historical.chargeRequest(scope))
                .isInstanceOf(HistoricalSalesReadBudgetException.class);
        assertThat(states.find(connection.getId(), false).orElseThrow().requestAttempts()).isEqualTo(200);
        coordinator.pauseHistoricalDailyBudget(job.id(), WORKER, Duration.ofHours(16).plusSeconds(1));
        assertThat(routine.get(job.id()).status()).isEqualTo(SyncJobStatus.WAITING_RETRY);
        assertThat(routine.get(job.id()).attemptCount()).isZero();
        assertThat(routine.get(job.id()).nextAttemptAt()).isEqualTo(Instant.parse("2026-10-06T22:00:01Z"));
        clock.set(NOW.plus(Duration.ofDays(1)));
        var resumed = coordinator.claimNext(WORKER).orElseThrow();
        historical.chargeRequest(new HistoricalSalesReadScope.Context(job.id(), WORKER, resumed.attemptCount(),
                resumed.windowStart(), resumed.windowEnd()));
        assertThat(states.find(connection.getId(), false).orElseThrow().requestAttempts()).isEqualTo(1);
    }

    @Test
    void pendingHistoryYieldsToNewRoutineDayWithoutCursorProgress() {
        successfulJob(SyncJobType.INCREMENTAL, null, routineStart(), routineEnd());
        var job = historical.enqueue().orElseThrow();
        clock.set(NOW.plus(Duration.ofDays(1)));
        var next = routine.createScheduledIncremental().orElseThrow();
        assertThat(next.jobType()).isEqualTo(SyncJobType.INCREMENTAL);
        assertThat(routine.get(job.id()).status()).isEqualTo(SyncJobStatus.CANCELLED);
        assertThat(states.find(connection.getId(), false).orElseThrow().cursorStart()).isEqualTo(START);
    }

    @Test
    void partialFetchOrCancelledLeaseCannotPublishAbsenceDeletion() {
        existingSale("Initial label");
        successfulJob(SyncJobType.INCREMENTAL, null, routineStart(), routineEnd());
        var job = historical.enqueue().orElseThrow();
        var claim = coordinator.claimNext(WORKER).orElseThrow();
        when(source.fetchSales("historical-store-b", claim.windowStart(), claim.windowEnd()))
                .thenThrow(new LiveSkladException("LiveSklad sales terminal page is incomplete"));
        assertThatThrownBy(() -> execution.execute(claim)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales_documents WHERE NOT is_deleted", Integer.class))
                .isEqualTo(1);
        assertLabel("Initial label");
        assertThat(states.find(connection.getId(), false).orElseThrow().cursorStart()).isEqualTo(START);
        doReturn(List.of()).when(source).fetchSales("historical-store-b", claim.windowStart(), claim.windowEnd());
        jdbc.update("UPDATE sync_jobs SET cancel_requested = true WHERE id = ?", job.id());
        assertThatThrownBy(() -> execution.execute(claim)).isInstanceOf(RuntimeException.class);
        assertLabel("Initial label");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM raw_record_versions", Integer.class)).isEqualTo(1);
        assertThat(states.find(connection.getId(), false).orElseThrow().cursorStart()).isEqualTo(START);
    }

    @Test
    void acceptedFactsBeforeWorkerCrashReplayIdempotentlyButCursorWaitsForFencedSuccess() {
        existingSale("Initial label");
        sourceSale("Corrected label");
        successfulJob(SyncJobType.INCREMENTAL, null, routineStart(), routineEnd());
        var job = historical.enqueue().orElseThrow();
        var first = coordinator.claimNext(WORKER).orElseThrow();
        execution.execute(first);
        assertLabel("Corrected label");
        assertThat(states.find(connection.getId(), false).orElseThrow().cursorStart()).isEqualTo(START);
        var before = jdbc.queryForMap("SELECT id, quantity, unit_price, net_amount, cost_amount, "
                + "analytics_category_id, category_assignment_id, condition_type_snapshot FROM sales_document_items");
        clock.set(NOW.plus(Duration.ofHours(2)).plusSeconds(1));
        assertThatThrownBy(() -> coordinator.completeStep(job.id(), WORKER)).isInstanceOf(IllegalStateException.class);
        assertThat(coordinator.claimNext("replacement-worker")).isEmpty();
        assertThatThrownBy(() -> execution.execute(first)).isInstanceOf(RuntimeException.class);
        clock.set(clock.instant().plus(Duration.ofMinutes(1)));
        var retry = coordinator.claimNext("replacement-worker").orElseThrow();
        assertThat(retry.attemptCount()).isEqualTo(1);
        execution.execute(retry);
        coordinator.completeStep(job.id(), "replacement-worker");
        assertLabel("Corrected label");
        assertThat(jdbc.queryForMap("SELECT id, quantity, unit_price, net_amount, cost_amount, "
                + "analytics_category_id, category_assignment_id, condition_type_snapshot FROM sales_document_items"))
                .isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM raw_record_versions", Integer.class)).isEqualTo(2);
        assertThat(states.find(connection.getId(), false).orElseThrow().cursorStart()).isEqualTo(job.periodEnd());
    }

    @Test
    void runningHistoryStopsBeforePublishingAndAllowsNextRoutineDay() {
        existingSale("Initial label");
        sourceSale("Corrected label");
        successfulJob(SyncJobType.INCREMENTAL, null, routineStart(), routineEnd());
        var job = historical.enqueue().orElseThrow();
        var claim = coordinator.claimNext(WORKER).orElseThrow();
        clock.set(NOW.plus(Duration.ofDays(1)));
        assertThat(routine.createScheduledIncremental()).isEmpty();
        assertThat(routine.get(job.id()).cancelRequested()).isTrue();
        jdbc.update("UPDATE sync_jobs SET lease_until = ? WHERE id = ?",
                Timestamp.from(clock.instant().plus(Duration.ofHours(2))), job.id());
        assertThatThrownBy(() -> execution.execute(claim)).isInstanceOf(RuntimeException.class);
        coordinator.retryOrFail(job.id(), WORKER, "cooperative cancellation", true, Duration.ZERO);
        assertThat(routine.get(job.id()).status()).isEqualTo(SyncJobStatus.CANCELLED);
        assertLabel("Initial label");
        assertThat(states.find(connection.getId(), false).orElseThrow().cursorStart()).isEqualTo(START);
        assertThat(routine.createScheduledIncremental().orElseThrow().jobType()).isEqualTo(SyncJobType.INCREMENTAL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PRICE", "MISSING", "CATEGORY"})
    void materialLinkedReturnChangesRollbackAndRequireBackfillCoveringDescendantDates(String correction) {
        existingSale("Initial label");
        linkedReturn();
        if (correction.equals("PRICE")) {
            sourceSale("Corrected label", new BigDecimal("13.00"));
        } else if (correction.equals("MISSING")) {
            when(source.fetchSales("historical-store-a", START, START.plus(Duration.ofHours(3))))
                    .thenReturn(List.of());
        } else {
            jdbc.update("UPDATE product_category_assignments SET valid_to = ? WHERE valid_to IS NULL",
                    Timestamp.from(START));
            jdbc.update("""
                    INSERT INTO product_category_assignments
                        (product_id, analytics_category_id, condition_type, assignment_source, rule_version,
                         valid_from, assigned_by, change_reason)
                    SELECT p.id, c.id, 'NOT_APPLICABLE', 'MANUAL', 'fixture-correction', ?, ?, 'Fixture'
                    FROM products p CROSS JOIN analytics_categories c WHERE c.code = 'CASE_APPLE_IPHONE'
                    """, Timestamp.from(START), admin.getId());
        }
        successfulJob(SyncJobType.INCREMENTAL, null, routineStart(), routineEnd());
        var job = historical.enqueue().orElseThrow();
        var claim = coordinator.claimNext(WORKER).orElseThrow();
        var failure = catchThrowableOfType(() -> execution.execute(claim), SalesSyncException.class);
        assertThat(failure).isNotNull().hasCauseInstanceOf(HistoricalSalesDependencyException.class);
        var dependency = (HistoricalSalesDependencyException) failure.getCause();
        assertLabelForSale("Initial label");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM raw_record_versions", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT net_amount FROM sales_documents WHERE document_kind = 'SALE'",
                BigDecimal.class)).isEqualByComparingTo("12.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales_documents WHERE is_deleted", Integer.class))
                .isZero();
        coordinator.failHistoricalDependency(job.id(), WORKER, dependency);
        jdbc.update("DELETE FROM sync_jobs WHERE id = ?", job.id());
        var blocked = states.find(connection.getId(), false).orElseThrow();
        assertThat(blocked.repairEnd()).isAfter(Instant.parse("2026-10-04T12:00:00Z"));
        assertThat(blocked.blockedEnd()).isEqualTo(job.periodEnd());
        clock.set(NOW.plusSeconds(1));
        successfulJob(SyncJobType.BACKFILL, admin, START, START.plus(Duration.ofDays(1)));
        assertThat(historical.enqueue()).isEmpty();
        successfulJob(SyncJobType.BACKFILL, admin, START, START.plus(Duration.ofDays(5)));
        assertThat(historical.enqueue()).isEmpty(); // Metadata alone is not accepted dependency repair.
        assertThat(states.find(connection.getId(), false).orElseThrow().linkedReturns()).hasSize(1);
    }

    @Test
    void coveringBackfillWithCompleteEmptyCashFeedCannotClearCashlessDependency() {
        existingSale("Initial label");
        linkedReturn();
        sourceSale("Corrected label");
        successfulJob(SyncJobType.INCREMENTAL, null, routineStart(), routineEnd());
        var job = historical.enqueue().orElseThrow();
        var claim = coordinator.claimNext(WORKER).orElseThrow();
        var failure = catchThrowableOfType(() -> execution.execute(claim), SalesSyncException.class);
        coordinator.failHistoricalDependency(job.id(), WORKER,
                (HistoricalSalesDependencyException) failure.getCause());
        clock.set(NOW.plusSeconds(1));
        // Real ordinary normalization publishes parent facts; the complete empty cash feed never fetches its child.
        sales.synchronize(new SalesSyncPeriod(START, START.plus(Duration.ofHours(3))));
        configureEmptyReturnFeed();
        var empty = returns.synchronize(new ReturnSyncPeriod(START, START.plus(Duration.ofDays(5))));
        assertThat(empty.status()).isEqualTo(com.storeanalytics.sync.model.SyncStatus.SUCCESS);
        assertLabelForSale("Corrected label");
        assertThat(jdbc.queryForObject("""
                SELECT i.product_name_snapshot FROM sales_document_items i
                JOIN sales_documents d ON d.id = i.sales_document_id WHERE d.document_kind = 'RETURN'
                """, String.class)).isEqualTo("Initial label");
        successfulJob(SyncJobType.BACKFILL, admin, START, START.plus(Duration.ofDays(5)));
        assertThat(historical.enqueue()).isEmpty();
        assertThat(states.find(connection.getId(), false).orElseThrow().cursorStart()).isEqualTo(START);
    }

    private void configureEmptyReturnFeed() {
        var raw = new ObjectMapper().createObjectNode();
        when(source.fetchCashItems()).thenReturn(List.of(new LiveSkladCashItemPayload(
                "historical-cash-item", "Fixture", "saleReturn", false, false, raw)));
        for (String store : List.of("historical-store-a", "historical-store-b")) {
            when(source.fetchCashRegisters(store)).thenReturn(List.of(new LiveSkladCashRegisterPayload(
                    "historical-register-" + store, "Fixture", store, raw)));
        }
        when(source.fetchCashTransactions(anyString(), anyString(), any(), any())).thenReturn(List.of());
    }

    @Test
    void actualKnownCashlessReturnRefreshHealsParentSnapshotWithoutParentMoneyEquality() {
        var blocked = blockLinkedCorrection();
        clock.set(NOW.plusSeconds(1));
        configureEmptyReturnFeed();
        configureKnownReturnDetail();
        runCoveringRepair(true);
        assertThat(jdbc.queryForObject("""
                SELECT i.product_name_snapshot FROM sales_document_items i
                JOIN sales_documents d ON d.id = i.sales_document_id WHERE d.document_kind = 'RETURN'
                """, String.class)).isEqualTo("Corrected label");
        assertThat(jdbc.queryForObject("SELECT net_amount FROM sales_documents WHERE document_kind = 'RETURN'",
                BigDecimal.class)).isEqualByComparingTo("6.00"); // Partial refund remains its own exact source fact.
        clock.set(NOW.plusSeconds(2));
        returns.synchronizeWebhookReturn("historical-return"); // Accepted same-version event recheck is idempotent.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM raw_record_versions WHERE entity_type = 'RETURN_DOCUMENT'
                """, Integer.class)).isEqualTo(1);
        assertThat(historical.enqueue().orElseThrow().periodStart()).isEqualTo(blocked.periodEnd());
        assertThat(states.find(connection.getId(), false).orElseThrow().linkedReturns()).isEmpty();
        assertThat(historical.enqueue()).isEmpty(); // The repair cannot advance the cursor twice.
        assertThat(states.find(connection.getId(), false).orElseThrow().cursorStart()).isEqualTo(blocked.periodEnd());
    }

    private SyncJobView blockLinkedCorrection() {
        return blockLinkedCorrection(false);
    }

    private SyncJobView blockLinkedCorrection(boolean acceptedChild) {
        existingSale("Initial label");
        linkedReturn();
        if (acceptedChild) {
            configureEmptyReturnFeed();
            configureKnownReturnDetail();
            returns.synchronizeWebhookReturn("historical-return");
        }
        sourceSale("Corrected label");
        successfulJob(SyncJobType.INCREMENTAL, null, routineStart(), routineEnd());
        var job = historical.enqueue().orElseThrow();
        var claim = coordinator.claimNext(WORKER).orElseThrow();
        var failure = catchThrowableOfType(() -> execution.execute(claim), SalesSyncException.class);
        coordinator.failHistoricalDependency(job.id(), WORKER,
                (HistoricalSalesDependencyException) failure.getCause());
        jdbc.update("DELETE FROM sync_jobs WHERE id = ?", job.id());
        assertThat(new HistoricalSalesRefreshStore(jdbc).find(connection.getId(), false)
                .orElseThrow().linkedReturns()).hasSize(1);
        return job;
    }

    @Test
    void rejectedOlderReturnRawCannotHealPersistedDependency() {
        blockLinkedCorrection(true);
        clock.set(NOW.plusSeconds(1));
        configureEmptyReturnFeed();
        runCoveringRepair(false);
        var latest = source.fetchReturnDetail("historical-return");
        var raw = (tools.jackson.databind.node.ObjectNode) latest.rawPayload().deepCopy();
        raw.put("dateChange", latest.occurredAt().minusSeconds(1).toString());
        when(source.fetchReturnDetail("historical-return")).thenReturn(new LiveSkladReturnDetailPayload(
                latest.externalId(), latest.documentNumber(), latest.occurredAt(), latest.occurredAt().minusSeconds(1),
                latest.sourceType(), latest.storeExternalId(), latest.processingEmployeeExternalId(),
                latest.originalSaleExternalId(), latest.cashAmount(), latest.cardAmount(), latest.bankTransferAmount(),
                latest.positions(), raw));
        returns.synchronizeWebhookReturn("historical-return");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM raw_record_versions WHERE entity_type = 'RETURN_DOCUMENT'
                AND normalization_status = 'NORMALIZED'
                """, Integer.class)).isEqualTo(2);
        assertThat(historical.enqueue()).isEmpty();
        assertThat(states.find(connection.getId(), false).orElseThrow().cursorStart()).isEqualTo(START);
    }

    @Test
    void requiredDependencyCohortCannotBecomeVacuousAfterJobRetention() {
        blockLinkedCorrection();
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE historical_sales_refresh_state SET repair_return_ids = '{}', repair_parent_ids = '{}'
                """)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(new HistoricalSalesRefreshStore(jdbc).find(connection.getId(), false)
                .orElseThrow().repairDependenciesRequired()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING_RAW", "WRONG_SCOPE", "NAME", "RELINKED", "LATE_PARENT", "LATE_PARENT_ITEM",
            "MISSING_TARGET", "MOVED_PARENT", "MARKED_DELETED", "UNSUPPORTED_SOURCE", "EMPLOYEE"})
    void invalidOrLaterChangedDependencyNeverClearsBlock(String change) {
        blockLinkedCorrection();
        clock.set(NOW.plusSeconds(1));
        configureEmptyReturnFeed();
        configureKnownReturnDetail();
        runCoveringRepair(true);
        switch (change) {
            case "MISSING_RAW" -> jdbc.update("""
                    UPDATE sales_documents SET raw_record_version_id = NULL WHERE document_kind = 'RETURN'
                    """);
            case "WRONG_SCOPE" -> jdbc.update("""
                    UPDATE sales_documents r SET last_sync_run_id = p.last_sync_run_id
                    FROM sales_documents p WHERE r.document_kind = 'RETURN' AND p.document_kind = 'SALE'
                    """);
            case "NAME" -> jdbc.update("""
                    UPDATE sales_document_items i SET product_name_snapshot = 'Unresolved snapshot'
                    FROM sales_documents d WHERE d.id = i.sales_document_id AND d.document_kind = 'RETURN'
                    """);
            case "RELINKED" -> jdbc.update("""
                    UPDATE sales_document_items i SET original_item_id = NULL
                    FROM sales_documents d WHERE d.id = i.sales_document_id AND d.document_kind = 'RETURN'
                    """);
            case "LATE_PARENT" -> sales.synchronize(new SalesSyncPeriod(START, START.plus(Duration.ofHours(3))));
            case "LATE_PARENT_ITEM" -> jdbc.update("""
                    UPDATE sales_document_items i SET cost_amount = i.cost_amount + 1
                    FROM sales_documents d WHERE d.id = i.sales_document_id AND d.document_kind = 'SALE'
                    """);
            case "MISSING_TARGET" -> {
                jdbc.update("""
                        DELETE FROM sales_document_items i USING sales_documents d
                        WHERE d.id = i.sales_document_id AND d.document_kind = 'RETURN'
                        """);
                jdbc.update("DELETE FROM sales_documents WHERE document_kind = 'RETURN'");
            }
            case "MOVED_PARENT" -> jdbc.update("""
                    UPDATE sales_documents SET occurred_at = '2026-11-01', business_date = '2026-11-01'
                    WHERE document_kind = 'SALE'
                    """);
            case "MARKED_DELETED" -> jdbc.update("""
                    UPDATE sales_documents SET is_deleted = true WHERE document_kind = 'RETURN'
                    """);
            case "UNSUPPORTED_SOURCE" -> jdbc.update("""
                    UPDATE sales_documents SET source_document_type = 'orderReturn' WHERE document_kind = 'RETURN'
                    """);
            case "EMPLOYEE" -> {
                jdbc.update("""
                        INSERT INTO employees (connection_id, source_system, external_id, full_name)
                        VALUES (?, 'LIVESKLAD', 'historical-other-employee', 'Synthetic fixture')
                        ON CONFLICT (connection_id, external_id) WHERE connection_id IS NOT NULL DO NOTHING
                        """, connection.getId());
                jdbc.update("""
                        UPDATE sales_documents SET employee_id =
                            (SELECT id FROM employees WHERE external_id = 'historical-other-employee')
                        WHERE document_kind = 'RETURN'
                        """);
            }
            default -> throw new IllegalArgumentException("Unknown fixture change");
        }
        assertThat(historical.enqueue()).isEmpty();
        assertThat(new HistoricalSalesRefreshStore(jdbc).find(connection.getId(), false)
                .orElseThrow().linkedReturns()).hasSize(1);
    }

    @Test
    void explicitAcceptedCashDeletionCanHealKnownDependencyWithoutResurrectingReturn() {
        var blocked = blockLinkedCorrection();
        clock.set(NOW.plusSeconds(1));
        configureEmptyReturnFeed();
        var raw = new ObjectMapper().createObjectNode().put("type", "delete");
        raw.putObject("document").put("id", "historical-return");
        var at = Instant.parse("2026-10-04T12:00:00Z");
        var deleted = new LiveSkladCashTransactionPayload("historical-delete", at, clock.instant(), "delete",
                "historical-store-a", "historical-register-historical-store-a", "historical-cash-item",
                "saleReturn", false, false, false, new BigDecimal("12.00"), null, null, "historical-return", raw);
        when(source.fetchCashTransactions(org.mockito.ArgumentMatchers.eq(deleted.cashRegisterExternalId()),
                anyString(), any(), any())).thenReturn(List.of(deleted));
        runCoveringRepair(true);
        assertThat(jdbc.queryForObject("SELECT is_deleted FROM sales_documents WHERE document_kind = 'RETURN'",
                Boolean.class)).isTrue();
        assertThat(historical.enqueue().orElseThrow().periodStart()).isEqualTo(blocked.periodEnd());
    }

    private void configureKnownReturnDetail() {
        var raw = new ObjectMapper().createObjectNode().put("id", "historical-return")
                .put("type", "saleReturn").put("parentDocumentId", "historical-sale")
                .put("date", "2026-10-04T12:00:00Z").putNull("dateChange");
        var position = new LiveSkladReturnPositionPayload("historical-return-position", "historical-position",
                "historical-product", "historical-product", null, "Source-local return", false,
                new BigDecimal("0.5"), new BigDecimal("12.00"), new BigDecimal("12.00"), new BigDecimal("4.00"));
        raw.putArray("positions").addObject().put("positionId", position.externalId())
                .put("salePositionId", position.originalSalePositionExternalId());
        when(source.fetchReturnDetail("historical-return")).thenReturn(new LiveSkladReturnDetailPayload(
                "historical-return", "Synthetic return", Instant.parse("2026-10-04T12:00:00Z"), null,
                "saleReturn", "historical-store-a", null, "historical-sale", BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, List.of(position), raw));
    }

    private SyncJob runCoveringRepair(boolean readReturn) {
        var repair = jobs.saveAndFlush(SyncJob.create(new SyncJobDefinition(connection, admin, SyncJobType.BACKFILL,
                START, START.plus(Duration.ofDays(5)), Duration.ofDays(5), 5), clock.instant()));
        // Reference and ORDER phases are metadata fixtures; both financially relevant phases call real services.
        for (int i = 0; i < 2; i++) {
            repair.claim(WORKER, Duration.ofHours(2), clock.instant());
            repair.completeStep(WORKER, clock.instant());
        }
        repair.claim(WORKER, Duration.ofHours(2), clock.instant());
        repair = jobs.saveAndFlush(repair);
        var context = new SyncExecutionContext(SyncTriggerType.INITIAL, repair.getId(), admin, 0);
        sales.synchronize(new SalesSyncPeriod(START, START.plus(Duration.ofHours(3))), context);
        repair.completeStep(WORKER, clock.instant());
        repair.claim(WORKER, Duration.ofHours(2), clock.instant());
        repair = jobs.saveAndFlush(repair);
        if (readReturn) {
            returns.synchronize(new ReturnSyncPeriod(START, START.plus(Duration.ofDays(5))), context);
        }
        repair.completeStep(WORKER, clock.instant());
        repair.claim(WORKER, Duration.ofHours(2), clock.instant());
        repair.completeStep(WORKER, clock.instant());
        return jobs.saveAndFlush(repair);
    }

    @Test
    void leaseExpiringDuringNormalizationRollsBackRawAndFactPublication() {
        existingSale("Initial label");
        sourceSale("Corrected label");
        successfulJob(SyncJobType.INCREMENTAL, null, routineStart(), routineEnd());
        var job = historical.enqueue().orElseThrow();
        var claim = coordinator.claimNext(WORKER).orElseThrow();
        doAnswer(call -> {
            call.callRealMethod();
            clock.set(NOW.plus(Duration.ofHours(3)));
            return null;
        }).when(dependencies).verify(any());
        assertThatThrownBy(() -> execution.execute(claim)).isInstanceOf(SalesSyncException.class);
        assertLabel("Initial label");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM raw_record_versions", Integer.class)).isEqualTo(1);
        assertThat(states.find(connection.getId(), false).orElseThrow().cursorStart()).isEqualTo(START);
    }

    @Test
    void sourceClockOnlyCorrectionWithLinkedReturnPreservesSnapshotTuple() {
        existingSale("Initial label");
        linkedReturn();
        sourceSale("Initial label");
        var original = source.fetchSaleDetail("historical-sale");
        var raw = (tools.jackson.databind.node.ObjectNode) original.rawPayload().deepCopy();
        raw.put("dateChange", NOW.toString());
        when(source.fetchSaleDetail("historical-sale")).thenReturn(new LiveSkladSaleDetailPayload(
                original.externalId(), original.documentNumber(), original.occurredAt(), NOW, original.sourceType(),
                original.storeExternalId(), null, null, original.cashAmount(), original.cardAmount(),
                original.bankTransferAmount(), original.positions(), raw));
        var before = jdbc.queryForMap("""
                SELECT i.id, i.analytics_category_id, i.category_assignment_id, i.classification_version,
                    i.condition_type_snapshot, i.quantity, i.unit_price, i.net_amount, i.cost_amount
                FROM sales_document_items i JOIN sales_documents d ON d.id = i.sales_document_id
                WHERE d.document_kind = 'SALE'
                """);
        successfulJob(SyncJobType.INCREMENTAL, null, routineStart(), routineEnd());
        var job = historical.enqueue().orElseThrow();
        var claim = coordinator.claimNext(WORKER).orElseThrow();
        execution.execute(claim);
        coordinator.completeStep(job.id(), WORKER);
        assertLabelForSale("Initial label");
        assertThat(jdbc.queryForMap("""
                SELECT i.id, i.analytics_category_id, i.category_assignment_id, i.classification_version,
                    i.condition_type_snapshot, i.quantity, i.unit_price, i.net_amount, i.cost_amount
                FROM sales_document_items i JOIN sales_documents d ON d.id = i.sales_document_id
                WHERE d.document_kind = 'SALE'
                """)).isEqualTo(before);
        assertThat(states.find(connection.getId(), false).orElseThrow().cursorStart()).isEqualTo(job.periodEnd());
        assertThat(jdbc.queryForObject("""
                SELECT i.product_name_snapshot FROM sales_document_items i
                JOIN sales_documents d ON d.id = i.sales_document_id WHERE d.document_kind = 'RETURN'
                """, String.class)).isEqualTo("Initial label"); // RETURN snapshot is not independently re-read here.
    }

    @Test
    void linkedReturnLabelCorrectionBlocksRealSetupServiceProjectionSkew() {
        jdbc.update("""
                UPDATE product_category_assignments SET analytics_category_id =
                    (SELECT id FROM analytics_categories WHERE code = 'SETUP_SERVICE')
                """);
        existingSale("Ремонт");
        linkedReturn();
        assertThat(setupServiceNumerator(LocalDate.of(2026, 10, 1))).isZero();
        assertThat(setupServiceNumerator(LocalDate.of(2026, 10, 4))).isZero();
        // The real view uses each item's own name. This local rollback demonstrates the dependency.
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("""
                    UPDATE sales_document_items i SET product_name_snapshot = 'Настройка'
                    FROM sales_documents d WHERE d.id = i.sales_document_id AND d.document_kind = 'SALE'
                    """);
            assertThat(setupServiceNumerator(LocalDate.of(2026, 10, 1))).isEqualByComparingTo("1");
            assertThat(setupServiceNumerator(LocalDate.of(2026, 10, 4))).isZero();
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM attach_rate_ordinary_item_facts_v4_catalog
                    WHERE classification_issue_code IS NOT NULL
                    """, Integer.class)).isZero();
            status.setRollbackOnly();
        });
        sourceSale("Настройка");
        successfulJob(SyncJobType.INCREMENTAL, null, routineStart(), routineEnd());
        var job = historical.enqueue().orElseThrow();
        var claim = coordinator.claimNext(WORKER).orElseThrow();
        var failure = catchThrowableOfType(() -> execution.execute(claim), SalesSyncException.class);
        assertThat(failure).isNotNull().hasCauseInstanceOf(HistoricalSalesDependencyException.class);
        var dependency = (HistoricalSalesDependencyException) failure.getCause();
        assertThat(dependency.repairEnd()).isAfter(Instant.parse("2026-10-04T12:00:00Z"));
        assertThat(jdbc.queryForList("SELECT product_name_snapshot FROM sales_document_items", String.class))
                .containsExactlyInAnyOrder("Ремонт", "Ремонт");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM raw_record_versions", Integer.class)).isEqualTo(1);
        assertThat(setupServiceNumerator(LocalDate.of(2026, 10, 1))).isZero();
        assertThat(setupServiceNumerator(LocalDate.of(2026, 10, 4))).isZero();
        assertThat(states.find(connection.getId(), false).orElseThrow().cursorStart()).isEqualTo(START);
    }

    private BigDecimal setupServiceNumerator(LocalDate date) {
        return jdbc.queryForObject("""
                SELECT coalesce(sum(net_quantity) FILTER
                    (WHERE 'SETUP_SERVICE' = ANY(numerator_metric_codes)), 0)
                FROM attach_rate_ordinary_item_facts_v4_catalog
                WHERE business_date = ? AND store_id IN
                    (SELECT id FROM stores WHERE connection_id = ? AND external_id = 'historical-store-a')
                """, BigDecimal.class, date, connection.getId());
    }

    @Test
    void sharedSaleReturnLockSerializesIndependentTransactionsRegardlessOfRoleFlags() throws Exception {
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var waiting = new CountDownLatch(1);
        var transaction = new TransactionTemplate(transactions);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> transaction.execute(status -> {
                dependencies.lockConnection(connection.getId());
                held.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("fixture publication release timed out");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return "SALE_PUBLISHED";
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> transaction.execute(status -> {
                waiting.countDown();
                dependencies.lockConnection(connection.getId());
                return "RETURN_CAN_READ_PARENT";
            }));
            try {
                assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("SALE_PUBLISHED");
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("RETURN_CAN_READ_PARENT");
        } finally {
            release.countDown();
        }
    }

    @Test
    void dependencyCaptureDoesNotInvertRawRetentionLockOrder() throws Exception {
        existingSale("Initial label");
        sourceSale("Corrected label");
        var captured = new CountDownLatch(1);
        var retained = new CountDownLatch(1);
        var transaction = new TransactionTemplate(transactions);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var publication = pool.submit(() -> transaction.execute(status -> {
                dependencies.lockConnection(connection.getId());
                var store = jdbc.queryForObject("SELECT id FROM stores WHERE external_id = 'historical-store-a'",
                        UUID.class);
                var model = com.storeanalytics.store.model.Store.fromLiveSklad(
                        connection, "historical-store-a", "Fixture", null);
                org.springframework.test.util.ReflectionTestUtils.setField(model, "id", store);
                var before = dependencies.capture(connection.getId(),
                        new SalesSyncPeriod(START, START.plusSeconds(10800)),
                        List.of(new StoreSalesBatch(model, List.of())));
                captured.countDown();
                try {
                    if (!retained.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("fixture retention was blocked by capture");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                sales.synchronize(new SalesSyncPeriod(START, START.plusSeconds(10800)));
                dependencies.verify(before);
                return true;
            }));
            assertThat(captured.await(5, TimeUnit.SECONDS)).isTrue();
            var retention = pool.submit(() -> transaction.execute(status -> {
                jdbc.queryForList("SELECT id FROM raw_record_versions FOR UPDATE");
                jdbc.update("DELETE FROM raw_record_versions"); // Actual V12 FK clears document raw reference.
                return true;
            }));
            try {
                assertThat(retention.get(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                retained.countDown();
            }
            assertThat(publication.get(5, TimeUnit.SECONDS)).isTrue();
            assertLabel("Corrected label");
        } finally {
            retained.countDown();
        }
    }

    private void linkedReturn() {
        jdbc.update("""
                INSERT INTO sales_documents
                    (connection_id, source_system, external_id, store_id, original_document_id, document_number,
                     document_kind, source_document_type, occurred_at, business_date, net_amount, cost_amount,
                     last_sync_run_id)
                SELECT connection_id, 'LIVESKLAD', 'historical-return', store_id, id, 'Synthetic return',
                    'RETURN', 'saleReturn', '2026-10-04T12:00:00Z', '2026-10-04', 12, 8, last_sync_run_id
                FROM sales_documents WHERE document_kind = 'SALE'
                """);
        jdbc.update("""
                INSERT INTO sales_document_items
                    (sales_document_id, external_id, original_item_id, product_id, product_name_snapshot,
                     analytics_category_id, category_assignment_id, classification_version, condition_type_snapshot,
                     quantity, unit_price, gross_amount, discount_amount, net_amount, cost_amount,
                     cost_quality, is_work)
                SELECT d.id, 'historical-return-position', i.id, i.product_id, i.product_name_snapshot,
                    i.analytics_category_id, i.category_assignment_id, i.classification_version,
                    i.condition_type_snapshot,
                    i.quantity, i.unit_price, i.gross_amount, i.discount_amount, i.net_amount, i.cost_amount,
                    i.cost_quality, i.is_work
                FROM sales_documents d JOIN sales_document_items i ON i.sales_document_id = d.original_document_id
                WHERE d.document_kind = 'RETURN'
                """);
    }

    private void existingSale(String label) {
        sourceSale(label);
        sales.synchronize(new SalesSyncPeriod(START, START.plus(Duration.ofHours(3))));
        assertLabel(label);
    }

    private void sourceSale(String label) {
        sourceSale(label, new BigDecimal("12.00"));
    }

    private void sourceSale(String label, BigDecimal sold) {
        var raw = new ObjectMapper().createObjectNode().put("id", "historical-sale").putNull("dateChange");
        raw.putArray("positions").addObject().put("positionId", "historical-position").put("name", label);
        raw.put("soldPrice", sold);
        var at = START.plus(Duration.ofHours(1));
        var summary = new LiveSkladSaleSummaryPayload("historical-sale", "Synthetic fixture", at, "sale",
                new BigDecimal("15.00"), sold, new BigDecimal("8.00"), raw);
        var position = new LiveSkladSalePositionPayload("historical-position", "historical-product",
                "historical-product", null, label, false, BigDecimal.ONE,
                new BigDecimal("15.00"), sold, new BigDecimal("8.00"));
        var detail = new LiveSkladSaleDetailPayload("historical-sale", "Synthetic fixture", at, null, "sale",
                "historical-store-a", null, null, sold, BigDecimal.ZERO, BigDecimal.ZERO,
                List.of(position), raw);
        when(source.fetchSales("historical-store-a", START, START.plus(Duration.ofHours(3))))
                .thenReturn(List.of(summary));
        when(source.fetchSaleDetail("historical-sale")).thenReturn(detail);
    }

    private void assertLabel(String expected) {
        assertThat(jdbc.queryForObject("SELECT product_name_snapshot FROM sales_document_items WHERE NOT is_deleted",
                String.class)).isEqualTo(expected);
    }

    private void assertLabelForSale(String expected) {
        assertThat(jdbc.queryForObject("""
                SELECT i.product_name_snapshot FROM sales_document_items i
                JOIN sales_documents d ON d.id = i.sales_document_id
                WHERE d.document_kind = 'SALE' AND NOT i.is_deleted AND NOT d.is_deleted
                """, String.class)).isEqualTo(expected);
    }

    private void successfulJob(SyncJobType type, AppUser actor, Instant start, Instant end) {
        SyncJob job = SyncJob.create(new SyncJobDefinition(connection, actor, type, start, end,
                Duration.between(start, end), 5), clock.instant());
        while (job.getStatus() != SyncJobStatus.SUCCESS) {
            job.claim(WORKER, Duration.ofHours(2), clock.instant());
            job.completeStep(WORKER, clock.instant());
        }
        jobs.saveAndFlush(job);
    }

    private Instant routineStart() {
        return LocalDate.now(clock.withZone(ZONE)).minusDays(3).atStartOfDay(ZONE).toInstant();
    }

    private Instant routineEnd() {
        return LocalDate.now(clock.withZone(ZONE)).atStartOfDay(ZONE).toInstant();
    }

    @TestConfiguration
    static class TimeConfiguration {
        @Bean @Primary
        MutableClock historicalClock() {
            return new MutableClock();
        }
    }

    static class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(NOW);

        void set(Instant value) {
            now.set(value);
        }

        @Override public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        @Override public Instant instant() {
            return now.get();
        }
    }
}
