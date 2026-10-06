package com.storeanalytics.sync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeanalytics.integration.connection.model.IntegrationConnection;
import com.storeanalytics.integration.connection.repository.IntegrationConnectionRepository;
import com.storeanalytics.integration.livesklad.client.LiveSkladClient;
import com.storeanalytics.integration.livesklad.dto.LiveSkladCashItemPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladCashRegisterPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladCashTransactionPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladReturnDetailPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladReturnPositionPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladSaleDetailPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladSalePositionPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladSaleSummaryPayload;
import com.storeanalytics.integration.livesklad.exception.LiveSkladException;
import com.storeanalytics.integration.livesklad.exception.LiveSkladReturnChangedException;
import com.storeanalytics.sync.exception.ReturnSyncCapacityException;
import com.storeanalytics.sync.exception.ReturnSyncException;
import com.storeanalytics.sync.model.SyncJob;
import com.storeanalytics.sync.model.SyncJobDefinition;
import com.storeanalytics.sync.model.SyncJobPhase;
import com.storeanalytics.sync.model.SyncJobType;
import com.storeanalytics.sync.model.SyncPeriod;
import com.storeanalytics.sync.model.SyncRun;
import com.storeanalytics.sync.model.SyncStatus;
import com.storeanalytics.sync.model.SyncTriggerType;
import com.storeanalytics.sync.repository.SyncJobRepository;
import com.storeanalytics.sync.repository.SyncRunRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(properties = "app.sync.worker-enabled=false")
@Testcontainers(disabledWithoutDocker = true)
class KnownReturnBackfillIntegrationTest {
    private static final Instant START = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-10-05T00:00:00Z");
    private static final Instant CHILD_AT = Instant.parse("2026-10-04T12:00:00Z");
    private static final String STORE = "known-return-store-a";
    private static final String CHILD = "known-return-child";
    private static final String WORKER = "known-return-fixture-worker";
    @Container private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    @Autowired private ReturnSyncService returns;
    @Autowired private SalesSyncService sales;
    @Autowired private SyncJobRepository jobs;
    @Autowired private SyncRunRepository runs;
    @Autowired private IntegrationConnectionRepository connections;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Clock clock;
    @Autowired private ObjectMapper mapper;
    @MockitoBean private LiveSkladClient source;
    private IntegrationConnection connection;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void fixtures() {
        for (String table : List.of("historical_sales_refresh_state", "sync_run_errors", "data_quality_issues",
                "sales_payments", "sales_document_items", "sales_documents", "raw_record_versions",
                "sync_runs", "sync_jobs", "cash_registers", "product_category_assignments")) {
            jdbc.update("DELETE FROM " + table);
        }
        connection = connections.findByConnectionKeyAndActiveTrue("livesklad-default").orElseThrow();
        for (String store : List.of(STORE, "known-return-store-inactive")) {
            jdbc.update("""
                    INSERT INTO stores (connection_id, source_system, external_id, name)
                    VALUES (?, 'LIVESKLAD', ?, 'Synthetic fixture')
                    ON CONFLICT (connection_id, external_id) WHERE connection_id IS NOT NULL
                        AND external_id IS NOT NULL DO UPDATE SET is_active = true
                    """, connection.getId(), store);
        }
        for (String employee : List.of("known-seller-before", "known-seller-after")) {
            jdbc.update("""
                    INSERT INTO employees (connection_id, source_system, external_id, full_name)
                    VALUES (?, 'LIVESKLAD', ?, 'Synthetic fixture')
                    ON CONFLICT (connection_id, external_id) WHERE connection_id IS NOT NULL DO NOTHING
                    """, connection.getId(), employee);
        }
        jdbc.update("""
                INSERT INTO products (connection_id, source_system, external_id, code, name, source_kind)
                VALUES (?, 'LIVESKLAD', 'known-return-product', 'fixture', 'Synthetic catalog', 'UNKNOWN')
                ON CONFLICT (connection_id, external_id) WHERE connection_id IS NOT NULL DO NOTHING
                """, connection.getId());
        when(source.fetchSales(anyString(), any(), any())).thenReturn(List.of());
        when(source.fetchCashItems()).thenReturn(List.of(new LiveSkladCashItemPayload("known-cash-item", "Fixture",
                "saleReturn", false, false, mapper.createObjectNode().put("id", "known-cash-item"))));
        when(source.fetchCashRegisters(anyString())).thenReturn(List.of());
        when(source.fetchCashTransactions(anyString(), anyString(), any(), any())).thenReturn(List.of());
        when(source.fetchReturnDetail(anyString())).thenAnswer(call -> detail(call.getArgument(0), STORE, CHILD_AT,
                CHILD_AT, "saleReturn", new BigDecimal("10.00")));
        parentSource("Earlier label", "known-seller-before");
        sales.synchronize(new SalesSyncPeriod(START, END));
        seedChild(CHILD, CHILD_AT, false);
    }

    @Test
    void refreshesKnownCashlessReturnAfterParentPublication() {
        SyncJob job = runningJob(SyncJobType.BACKFILL, SyncJobPhase.SALES);
        parentSource("Revised label", "known-seller-after");
        sales.synchronize(new SalesSyncPeriod(START, END), context(job, SyncTriggerType.INITIAL));
        assertThat(childLabel()).isEqualTo("Earlier label");
        job.completeStep(WORKER, clock.instant());
        job.claim(WORKER, Duration.ofHours(2), clock.instant());
        job = jobs.saveAndFlush(job);
        ReturnSyncResult result = returns.synchronize(period(), context(job, SyncTriggerType.INITIAL));
        assertThat(result.status()).isEqualTo(SyncStatus.SUCCESS);
        assertThat(result.recordsFetched()).isEqualTo(1);
        assertThat(childLabel()).isEqualTo("Revised label");
        assertThat(jdbc.queryForObject("""
                SELECT r.employee_id = p.employee_id AND cr.sync_scope = 'RETURNS'
                    AND cr.sync_job_id = ? AND r.updated_at > p.updated_at
                FROM sales_documents r JOIN sales_documents p ON p.id = r.original_document_id
                JOIN sync_runs cr ON cr.id = r.last_sync_run_id WHERE r.external_id = ?
                """, Boolean.class, job.getId(), CHILD)).isTrue();
        assertThat(returns.synchronize(period(), context(job, SyncTriggerType.INITIAL)).recordsFetched()).isEqualTo(1);
        assertThat(childLabel()).isEqualTo("Revised label");
    }

    @Test
    void cashPaidKnownReturnIsFetchedExactlyOnce() {
        cash(CHILD, false);
        var job = runningJob(SyncJobType.BACKFILL, SyncJobPhase.RETURNS);
        assertThat(returns.synchronize(period(), context(job, SyncTriggerType.INITIAL)).recordsFetched()).isEqualTo(1);
        verify(source, times(1)).fetchReturnDetail(CHILD);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM raw_record_versions WHERE entity_type = 'RETURN_DOCUMENT'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void cashDeletionTakesPrecedenceOverRetainedActiveIdentity() {
        cash(CHILD, true);
        var job = runningJob(SyncJobType.BACKFILL, SyncJobPhase.RETURNS);
        assertThat(returns.synchronize(period(), context(job, SyncTriggerType.INITIAL)).documentsDeleted())
                .isEqualTo(1);
        verify(source, never()).fetchReturnDetail(anyString());
        assertThat(jdbc.queryForObject("SELECT is_deleted FROM sales_documents WHERE external_id = ?",
                Boolean.class, CHILD)).isTrue();
    }

    @Test
    void excludesInactiveForeignUnsupportedOutOfPeriodAndOwnerDeletedFacts() {
        seedChild("ignored-inactive", CHILD_AT, false);
        jdbc.update("UPDATE sales_documents SET store_id = (SELECT id FROM stores WHERE external_id = ?)"
                        + " WHERE external_id = ?",
                "known-return-store-inactive", "ignored-inactive");
        jdbc.update("UPDATE stores SET is_active = false WHERE external_id = ?", "known-return-store-inactive");
        seedForeignReturn();
        seedChild("ignored-type", CHILD_AT, false);
        jdbc.update("UPDATE sales_documents SET source_document_type = 'orderReturn'"
                + " WHERE external_id = 'ignored-type'");
        seedChild("ignored-before", START.minusSeconds(1), false);
        seedChild("ignored-end", END, false);
        seedChild("ignored-owner-deleted", CHILD_AT, true);
        var job = runningJob(SyncJobType.BACKFILL, SyncJobPhase.RETURNS);
        assertThat(returns.synchronize(period(), context(job, SyncTriggerType.INITIAL)).recordsFetched()).isEqualTo(1);
        verify(source, times(1)).fetchReturnDetail(CHILD);
        for (String id : List.of("ignored-inactive", "ignored-foreign", "ignored-type", "ignored-before",
                "ignored-end", "ignored-owner-deleted")) {
            verify(source, never()).fetchReturnDetail(id);
        }
        assertThat(jdbc.queryForObject("SELECT is_deleted FROM sales_documents"
                        + " WHERE external_id = 'ignored-owner-deleted'",
                Boolean.class)).isTrue();
    }

    @Test
    void zeroNetNullCostAndUnchangedSourceAreAcceptedWithoutInventedPayments() {
        when(source.fetchReturnDetail(CHILD)).thenReturn(
                detail(CHILD, STORE, CHILD_AT, null, "saleReturn", BigDecimal.ZERO));
        var job = runningJob(SyncJobType.BACKFILL, SyncJobPhase.RETURNS);
        assertThat(returns.synchronize(period(), context(job, SyncTriggerType.INITIAL)).status())
                .isEqualTo(SyncStatus.SUCCESS);
        assertThat(jdbc.queryForObject("SELECT net_amount FROM sales_documents WHERE external_id = ?",
                BigDecimal.class, CHILD)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT cost_amount FROM sales_documents WHERE external_id = ?",
                BigDecimal.class, CHILD)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales_payments WHERE sales_document_id ="
                + " (SELECT id FROM sales_documents WHERE external_id = ?)", Integer.class, CHILD)).isZero();
    }

    @Test
    void manualAndIncrementalDoNotRefreshKnownCashlessFacts() {
        assertThat(returns.synchronize(period()).recordsFetched()).isZero();
        var job = runningJob(SyncJobType.INCREMENTAL, SyncJobPhase.RETURNS);
        assertThat(returns.synchronize(period(), context(job, SyncTriggerType.SCHEDULED)).recordsFetched()).isZero();
        verify(source, never()).fetchReturnDetail(anyString());
    }

    @Test
    void seventyCombinedKnownAndCashCandidatesAreAllowed() {
        for (int i = 1; i < 69; i++) {
            seedChild("known-extra-" + i, CHILD_AT, false);
        }
        cash("cash-only", false);
        var job = runningJob(SyncJobType.BACKFILL, SyncJobPhase.RETURNS);
        assertThat(returns.synchronize(period(), context(job, SyncTriggerType.INITIAL)).recordsFetched()).isEqualTo(70);
        verify(source, times(70)).fetchReturnDetail(anyString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void seventyOneCombinedCandidatesFailBeforeDetailsAndPublication(boolean cashCandidate) {
        for (int i = 1; i < (cashCandidate ? 70 : 71); i++) {
            seedChild("known-extra-" + i, CHILD_AT, false);
        }
        if (cashCandidate) {
            cash("cash-only", false);
        }
        var job = runningJob(SyncJobType.BACKFILL, SyncJobPhase.RETURNS);
        int rawBefore = rawCount();
        assertThatThrownBy(() -> returns.synchronize(period(), context(job, SyncTriggerType.INITIAL)))
                .isInstanceOf(ReturnSyncCapacityException.class);
        verify(source, never()).fetchReturnDetail(anyString());
        assertThat(rawCount()).isEqualTo(rawBefore);
        assertFailedRunAndUnadvancedJob(job);
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-id", "wrong-store", "stale", "outside-period", "wrong-type", "failure"})
    void oneKnownDetailFailureCannotPublishAnyFacts(String failure) {
        seedChild("known-second", CHILD_AT, false);
        when(source.fetchReturnDetail(CHILD)).thenAnswer(call -> {
            if (failure.equals("failure")) {
                throw new LiveSkladException("Synthetic upstream failure");
            }
            return detail(failure.equals("wrong-id") ? "other-id" : CHILD,
                    failure.equals("wrong-store") ? "other-store" : STORE,
                    failure.equals("outside-period") ? END : CHILD_AT,
                    failure.equals("stale") ? CHILD_AT.minusSeconds(1) : CHILD_AT,
                    failure.equals("wrong-type") ? "sale" : "saleReturn", new BigDecimal("10.00"));
        });
        var job = runningJob(SyncJobType.BACKFILL, SyncJobPhase.RETURNS);
        int rawBefore = rawCount();
        assertThatThrownBy(() -> returns.synchronize(period(), context(job, SyncTriggerType.INITIAL)))
                .isInstanceOf(ReturnSyncException.class);
        assertThat(rawCount()).isEqualTo(rawBefore);
        assertThat(childLabel()).isEqualTo("Earlier label");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cash_registers", Integer.class)).isZero();
        assertFailedRunAndUnadvancedJob(job);
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-attempt", "wrong-window", "cancelled", "expired", "wrong-phase", "wrong-type"})
    void invalidDurableContextDoesNotRefreshOrPublish(String failure) {
        var job = runningJob(SyncJobType.BACKFILL, SyncJobPhase.RETURNS);
        SyncExecutionContext context = context(job, SyncTriggerType.INITIAL);
        if (failure.equals("wrong-attempt")) {
            context = new SyncExecutionContext(SyncTriggerType.INITIAL, job.getId(), null, 1);
        } else if (failure.equals("wrong-window")) {
            jdbc.update("UPDATE sync_jobs SET current_window_end = ? WHERE id = ?",
                    Timestamp.from(END.minusSeconds(1)), job.getId());
        } else if (failure.equals("cancelled")) {
            jdbc.update("UPDATE sync_jobs SET cancel_requested = true WHERE id = ?", job.getId());
        } else if (failure.equals("expired")) {
            jdbc.update("UPDATE sync_jobs SET lease_until = ? WHERE id = ?",
                    Timestamp.from(clock.instant().minusSeconds(1)), job.getId());
        } else if (failure.equals("wrong-phase")) {
            jdbc.update("UPDATE sync_jobs SET phase = 'SALES' WHERE id = ?", job.getId());
        } else {
            jdbc.update("UPDATE sync_jobs SET job_type = 'INCREMENTAL' WHERE id = ?", job.getId());
        }
        SyncExecutionContext requested = context;
        assertThatThrownBy(() -> returns.synchronize(period(), requested)).isInstanceOf(ReturnSyncException.class);
        verify(source, never()).fetchReturnDetail(anyString());
        assertThat(childLabel()).isEqualTo("Earlier label");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletionOrCancellationDuringFetchCannotPublish(boolean cancelJob) {
        var job = runningJob(SyncJobType.BACKFILL, SyncJobPhase.RETURNS);
        when(source.fetchReturnDetail(CHILD)).thenAnswer(call -> {
            if (cancelJob) {
                jdbc.update("UPDATE sync_jobs SET cancel_requested = true WHERE id = ?", job.getId());
            } else {
                jdbc.update("UPDATE sales_documents SET is_deleted = true WHERE external_id = ?", CHILD);
            }
            return detail(CHILD, STORE, CHILD_AT, CHILD_AT, "saleReturn", new BigDecimal("10.00"));
        });
        int rawBefore = rawCount();
        assertThatThrownBy(() -> returns.synchronize(period(), context(job, SyncTriggerType.INITIAL)))
                .isInstanceOf(ReturnSyncException.class);
        assertThat(rawCount()).isEqualTo(rawBefore);
        assertThat(childLabel()).isEqualTo("Earlier label");
        if (!cancelJob) {
            assertThat(jdbc.queryForObject("SELECT is_deleted FROM sales_documents WHERE external_id = ?",
                    Boolean.class, CHILD)).isTrue();
        }
    }

    @Test
    void interveningAcceptedWebhookAtEqualSourceClockCannotBeOverwrittenAndFreshRetryWorks() {
        var job = runningJob(SyncJobType.BACKFILL, SyncJobPhase.RETURNS);
        when(source.fetchCashRegisters(STORE)).thenReturn(List.of(new LiveSkladCashRegisterPayload(
                "known-register", "Synthetic fixture", STORE, mapper.createObjectNode().put("id", "known-register"))));
        var captured = detail(CHILD, STORE, CHILD_AT, null, "saleReturn", new BigDecimal("10.00"));
        var observed = detail(CHILD, STORE, CHILD_AT, null, "saleReturn", new BigDecimal("8.00"));
        ((ObjectNode) captured.rawPayload().get("positions").get(0)).put("soldPrice", new BigDecimal("10.00"));
        ((ObjectNode) observed.rawPayload().get("positions").get(0)).put("soldPrice", new BigDecimal("8.00"));
        AtomicBoolean first = new AtomicBoolean(true);
        AtomicReference<ReturnSyncResult> accepted = new AtomicReference<>();
        AtomicReference<BigDecimal> acceptedAmount = new AtomicReference<>();
        when(source.fetchReturnDetail(CHILD)).thenAnswer(call -> {
            if (first.compareAndSet(true, false)) {
                ReturnSyncResult published = returns.synchronizeWebhookReturn(CHILD);
                accepted.set(published);
                acceptedAmount.set(jdbc.queryForObject("SELECT net_amount FROM sales_documents WHERE external_id = ?",
                        BigDecimal.class, CHILD));
                return captured;
            }
            return observed;
        });
        Throwable failure = catchThrowable(() -> returns.synchronize(period(), context(job, SyncTriggerType.INITIAL)));
        assertThat(accepted.get()).isNotNull();
        assertThat(accepted.get().status()).isEqualTo(SyncStatus.SUCCESS);
        assertThat(acceptedAmount.get()).isEqualByComparingTo("8.00");
        assertThat(failure).isInstanceOf(ReturnSyncException.class)
                .hasRootCauseInstanceOf(LiveSkladReturnChangedException.class);
        assertThat(jdbc.queryForObject("SELECT net_amount FROM sales_documents WHERE external_id = ?",
                BigDecimal.class, CHILD)).isEqualByComparingTo("8.00");
        assertThat(jdbc.queryForObject("SELECT last_sync_run_id FROM sales_documents WHERE external_id = ?",
                UUID.class, CHILD)).isEqualTo(accepted.get().syncRunId());
        assertThat(jdbc.queryForObject("""
                SELECT i.unit_price FROM sales_document_items i
                JOIN sales_documents d ON d.id = i.sales_document_id WHERE d.external_id = ?
                """, BigDecimal.class, CHILD)).isEqualByComparingTo("8.00");
        assertThat(jdbc.queryForObject("""
                SELECT CAST(v.payload -> 'detail' -> 'positions' -> 0 ->> 'soldPrice' AS numeric)
                FROM sales_documents d JOIN raw_record_versions v ON v.id = d.raw_record_version_id
                WHERE d.external_id = ?
                """, BigDecimal.class, CHILD)).isEqualByComparingTo("8.00");
        assertThat(jdbc.queryForObject("SELECT status FROM sync_runs WHERE id = ?", String.class,
                ((ReturnSyncException) failure).getSyncRunId())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT cursor_start FROM sync_jobs WHERE id = ?", Timestamp.class,
                job.getId()).toInstant()).isEqualTo(START);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM raw_record_versions v JOIN sync_runs r ON r.id = v.last_sync_run_id
                WHERE r.sync_job_id = ?
                """, Integer.class, job.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cash_registers", Integer.class)).isZero();
        job.requestCancellation(clock.instant());
        job.completeStep(WORKER, clock.instant());
        jobs.saveAndFlush(job);
        var retry = runningJob(SyncJobType.BACKFILL, SyncJobPhase.RETURNS);
        assertThat(returns.synchronize(period(), context(retry, SyncTriggerType.INITIAL)).status())
                .isEqualTo(SyncStatus.SUCCESS);
        assertThat(jdbc.queryForObject("SELECT net_amount FROM sales_documents WHERE external_id = ?",
                BigDecimal.class, CHILD)).isEqualByComparingTo("8.00");
    }

    private SyncJob runningJob(SyncJobType type, SyncJobPhase phase) {
        SyncJob job = SyncJob.create(new SyncJobDefinition(connection, null, type, START, END,
                Duration.between(START, END), 5), clock.instant());
        while (job.getPhase() != phase) {
            job.claim(WORKER, Duration.ofHours(2), clock.instant());
            job.completeStep(WORKER, clock.instant());
        }
        job.claim(WORKER, Duration.ofHours(2), clock.instant());
        return jobs.saveAndFlush(job);
    }

    private SyncExecutionContext context(SyncJob job, SyncTriggerType trigger) {
        return new SyncExecutionContext(trigger, job.getId(), null, job.getAttemptCount());
    }

    private void assertFailedRunAndUnadvancedJob(SyncJob job) {
        assertThat(jdbc.queryForObject("SELECT status FROM sync_runs WHERE sync_scope = 'RETURNS'"
                        + " ORDER BY started_at DESC LIMIT 1",
                String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT cursor_start FROM sync_jobs WHERE id = ?", Timestamp.class,
                job.getId()).toInstant()).isEqualTo(START);
    }

    private int rawCount() {
        return jdbc.queryForObject("SELECT count(*) FROM raw_record_versions", Integer.class);
    }

    private String childLabel() {
        return jdbc.queryForObject("""
                SELECT i.product_name_snapshot FROM sales_document_items i
                JOIN sales_documents d ON d.id = i.sales_document_id WHERE d.external_id = ?
                """, String.class, CHILD);
    }

    private ReturnSyncPeriod period() {
        return new ReturnSyncPeriod(START, END);
    }

    private void seedChild(String id, Instant occurred, boolean deleted) {
        jdbc.update("""
                INSERT INTO sales_documents (connection_id, source_system, external_id, store_id, employee_id,
                    original_document_id, document_number, document_kind, source_document_type, occurred_at,
                    business_date, net_amount, cost_amount, source_updated_at, last_sync_run_id, is_deleted)
                SELECT connection_id, source_system, ?, store_id, employee_id, id, 'Synthetic return',
                    'RETURN', 'saleReturn', ?, CAST(? AS date), 10, NULL, ?, last_sync_run_id, ?
                FROM sales_documents WHERE external_id = 'known-return-parent'
                """, id, Timestamp.from(occurred), occurred.atZone(java.time.ZoneOffset.UTC).toLocalDate(),
                Timestamp.from(occurred), deleted);
        jdbc.update("""
                INSERT INTO sales_document_items (sales_document_id, external_id, original_item_id, product_id,
                    product_name_snapshot, source_group_name_snapshot, analytics_category_id, category_assignment_id,
                    classification_version, condition_type_snapshot, quantity, unit_price, gross_amount,
                    discount_amount,
                    net_amount, cost_amount, cost_quality, is_work, is_deleted)
                SELECT r.id, ?, original.id, original.product_id, original.product_name_snapshot,
                    original.source_group_name_snapshot, original.analytics_category_id,
                    original.category_assignment_id,
                    original.classification_version, original.condition_type_snapshot, 1, 10, 10, 0, 10, NULL,
                    'MISSING', false, ? FROM sales_documents r JOIN sales_document_items original
                    ON original.sales_document_id = r.original_document_id WHERE r.external_id = ?
                """, id + "-position", deleted, id);
    }

    private void seedForeignReturn() {
        jdbc.update("""
                INSERT INTO integration_connections (connection_key, source_system, display_name)
                VALUES ('known-return-other', 'LIVESKLAD', 'Synthetic fixture')
                ON CONFLICT (connection_key) DO NOTHING
                """);
        IntegrationConnection other = connections.findByConnectionKeyAndActiveTrue("known-return-other").orElseThrow();
        jdbc.update("""
                INSERT INTO stores (connection_id, source_system, external_id, name)
                VALUES (?, 'LIVESKLAD', 'known-return-foreign-store', 'Synthetic fixture')
                ON CONFLICT (connection_id, external_id) WHERE connection_id IS NOT NULL
                    AND external_id IS NOT NULL DO NOTHING
                """, other.getId());
        UUID store = jdbc.queryForObject("SELECT id FROM stores WHERE external_id = 'known-return-foreign-store'",
                UUID.class);
        SyncRun foreignRun = SyncRun.startSalesSync(other, new SyncPeriod(START, END), clock.instant());
        foreignRun.complete(1, 1, 0, 0, clock.instant());
        foreignRun = runs.saveAndFlush(foreignRun);
        jdbc.update("""
                INSERT INTO sales_documents (connection_id, source_system, external_id, store_id, document_number,
                    document_kind, source_document_type, occurred_at, business_date, net_amount, cost_amount,
                    source_updated_at, last_sync_run_id)
                SELECT ?, 'LIVESKLAD', 'known-return-foreign-parent', ?, 'Synthetic sale', 'SALE', 'sale',
                    occurred_at, business_date, net_amount, cost_amount, source_updated_at, ?
                FROM sales_documents WHERE external_id = 'known-return-parent'
                """, other.getId(), store, foreignRun.getId());
        jdbc.update("""
                INSERT INTO sales_documents (connection_id, source_system, external_id, store_id,
                    original_document_id, document_number, document_kind, source_document_type, occurred_at,
                    business_date, net_amount, source_updated_at, last_sync_run_id)
                SELECT connection_id, source_system, 'ignored-foreign', store_id, id, 'Synthetic return',
                    'RETURN', 'saleReturn', ?, CAST(? AS date), 10, ?, last_sync_run_id
                FROM sales_documents WHERE external_id = 'known-return-foreign-parent'
                """, Timestamp.from(CHILD_AT), CHILD_AT.atZone(java.time.ZoneOffset.UTC).toLocalDate(),
                Timestamp.from(CHILD_AT));
    }

    private void parentSource(String label, String employee) {
        Instant occurred = START.plusSeconds(3600);
        var raw = mapper.createObjectNode().put("id", "known-return-parent").put("type", "sale").putNull("dateChange");
        raw.putArray("positions").addObject().put("positionId", "known-return-parent-position").put("name", label);
        var amount = new BigDecimal("10.00");
        var summary = new LiveSkladSaleSummaryPayload("known-return-parent", "Synthetic sale", occurred, "sale",
                amount, amount, new BigDecimal("6.00"), raw);
        var position = new LiveSkladSalePositionPayload("known-return-parent-position", "known-return-product",
                "fixture", null, label, false, BigDecimal.ONE, amount, amount, new BigDecimal("6.00"));
        var detail = new LiveSkladSaleDetailPayload("known-return-parent", "Synthetic sale", occurred, null, "sale",
                STORE, employee, "Synthetic fixture", amount, BigDecimal.ZERO, BigDecimal.ZERO, List.of(position), raw);
        when(source.fetchSales(org.mockito.ArgumentMatchers.eq(STORE), any(), any())).thenReturn(List.of(summary));
        when(source.fetchSaleDetail("known-return-parent")).thenReturn(detail);
    }

    private LiveSkladReturnDetailPayload detail(String id, String store, Instant occurred, Instant version,
                                              String type, BigDecimal amount) {
        var raw = mapper.createObjectNode().put("id", id).put("type", type);
        raw.putObject("shop").put("id", store);
        raw.putObject("document").put("id", "known-return-parent");
        raw.putArray("positions").addObject().put("positionId", id + "-position");
        var item = new LiveSkladReturnPositionPayload(id + "-position", "known-return-parent-position",
                "known-return-product", "fixture", null, "Synthetic own label", false,
                BigDecimal.ONE, amount, amount, null);
        return new LiveSkladReturnDetailPayload(id, "Synthetic return", occurred, version, type, store,
                "known-seller-before", "known-return-parent", amount, BigDecimal.ZERO, BigDecimal.ZERO,
                List.of(item), raw);
    }

    private void cash(String id, boolean deleted) {
        var register = new LiveSkladCashRegisterPayload("known-register", "Synthetic fixture", STORE,
                mapper.createObjectNode().put("id", "known-register"));
        when(source.fetchCashRegisters(STORE)).thenReturn(List.of(register));
        var raw = mapper.createObjectNode().put("id", "known-cash-transaction")
                .put("type", deleted ? "delete" : "outflow");
        raw.putObject("document").put("id", id);
        var transaction = new LiveSkladCashTransactionPayload("known-cash-transaction", CHILD_AT,
                CHILD_AT.plusSeconds(60),
                deleted ? "delete" : "outflow", STORE, "known-register", "known-cash-item", "saleReturn", false, false,
                false, new BigDecimal("10.00"), null, null, id, raw);
        when(source.fetchCashTransactions(org.mockito.ArgumentMatchers.eq("known-register"), anyString(), any(), any()))
                .thenReturn(List.of(transaction));
    }
}
