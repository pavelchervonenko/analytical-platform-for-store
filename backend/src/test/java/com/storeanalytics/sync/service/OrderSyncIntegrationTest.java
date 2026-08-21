package com.storeanalytics.sync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.integration.livesklad.client.LiveSkladClient;
import com.storeanalytics.integration.livesklad.client.LiveSkladOrderClient;
import com.storeanalytics.integration.livesklad.dto.LiveSkladCashItemPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladCashRegisterPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladCashTransactionPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladEmployeePayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladOrderDetailPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladOrderPositionPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladOrderSummaryPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladReturnDetailPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladSaleDetailPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladSaleSummaryPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladStorePayload;
import com.storeanalytics.integration.livesklad.exception.LiveSkladOrderChangedException;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import com.storeanalytics.metrics.service.StoreKpiService;
import com.storeanalytics.sync.exception.OrderSyncException;
import com.storeanalytics.sync.model.SyncStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Import(OrderSyncIntegrationTest.FakeClientConfiguration.class)
class OrderSyncIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private StoreSyncService storeSyncService;
    @Autowired
    private EmployeeSyncService employeeSyncService;
    @Autowired
    private SalesSyncService salesSyncService;
    @Autowired
    private OrderSyncService orderSyncService;
    @Autowired
    private StoreKpiService storeKpiService;
    @Autowired
    private FakeLiveSkladOrderClient orderClient;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void cleanAndBootstrap() {
        jdbcTemplate.update("DELETE FROM data_quality_issues");
        jdbcTemplate.update("DELETE FROM sales_payments");
        jdbcTemplate.update("DELETE FROM sales_document_items");
        jdbcTemplate.update("DELETE FROM sales_documents");
        jdbcTemplate.update("DELETE FROM product_category_assignments");
        jdbcTemplate.update("DELETE FROM raw_record_versions");
        jdbcTemplate.update("DELETE FROM sync_run_errors");
        jdbcTemplate.update("DELETE FROM sync_runs");
        jdbcTemplate.update("DELETE FROM products");
        jdbcTemplate.update("DELETE FROM employee_store_assignments");
        jdbcTemplate.update("DELETE FROM employees");
        jdbcTemplate.update("DELETE FROM stores");
        orderClient.clear();
        storeSyncService.synchronize();
        employeeSyncService.synchronize();
    }

    @Test
    void importsIssuedWorkIdempotentlyAndPreservesItDuringSalesSync() {
        orderClient.set(order(
                "A000605",
                "Выдан",
                true,
                Instant.parse("2026-08-05T12:08:00Z"),
                Instant.parse("2026-08-05T15:00:00Z"),
                "18500.00"
        ));

        OrderSyncResult first = orderSyncService.synchronize(period());
        OrderSyncResult unchanged = orderSyncService.synchronize(period());

        assertThat(first.status()).isEqualTo(SyncStatus.SUCCESS);
        assertThat(first.recordsCreated()).isEqualTo(1);
        assertThat(first.itemsCreated()).isEqualTo(1);
        assertThat(unchanged.recordsSkipped()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap(
                """
                SELECT document.document_number,
                       document.source_document_type,
                       document.business_date,
                       document.net_amount,
                       document.cost_amount,
                       document.is_deleted,
                       employee.external_id AS employee_external_id,
                       category.code AS category_code
                FROM sales_documents document
                JOIN employees employee ON employee.id = document.employee_id
                JOIN sales_document_items item ON item.sales_document_id = document.id
                JOIN analytics_categories category
                  ON category.id = item.analytics_category_id
                WHERE document.external_id =
                      'order:order-1:position:position-1'
                """
        )).containsEntry("document_number", "A000605")
                .containsEntry("source_document_type", "orderPosition")
                .containsEntry("business_date", java.sql.Date.valueOf("2026-08-05"))
                .containsEntry("net_amount", money("18500.00"))
                .containsEntry("cost_amount", money("15000.00"))
                .containsEntry("is_deleted", false)
                .containsEntry("employee_external_id", "employee-kirill")
                .containsEntry("category_code", "SETUP_SERVICE");
        assertThat(storeKpiService.calculate(
                storeId(),
                new StoreKpiPeriod(
                        LocalDate.parse("2026-08-01"),
                        LocalDate.parse("2026-08-09")
                )
        ).netRevenue()).isEqualByComparingTo("18500.00");

        salesSyncService.synchronize(new SalesSyncPeriod(
                period().start(),
                period().end()
        ));
        assertThat(documentDeleted()).isFalse();
    }

    @Test
    void updatesChangedPositionAndDeletesItWhenOrderIsNoLongerIssued() {
        orderClient.set(order(
                "A000642",
                "Выдан",
                true,
                Instant.parse("2026-08-06T11:01:00Z"),
                Instant.parse("2026-08-06T14:00:00Z"),
                "6000.00"
        ));
        orderSyncService.synchronize(period());

        orderClient.set(order(
                "A000642",
                "Выдан",
                true,
                Instant.parse("2026-08-06T11:01:00Z"),
                Instant.parse("2026-08-06T15:00:00Z"),
                "6500.00"
        ));
        OrderSyncResult updated = orderSyncService.synchronize(period());

        assertThat(updated.recordsUpdated()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT net_amount FROM sales_documents",
                BigDecimal.class
        )).isEqualByComparingTo("6500.00");

        orderClient.set(order(
                "A000642",
                "Отменен",
                true,
                Instant.parse("2026-08-06T11:01:00Z"),
                Instant.parse("2026-08-06T16:00:00Z"),
                "6500.00"
        ));
        OrderSyncResult deleted = orderSyncService.synchronize(period());

        assertThat(deleted.documentsDeleted()).isEqualTo(1);
        assertThat(documentDeleted()).isTrue();
        assertThat(storeKpiService.calculate(
                storeId(),
                new StoreKpiPeriod(
                        LocalDate.parse("2026-08-01"),
                        LocalDate.parse("2026-08-09")
                )
        ).netRevenue()).isEqualByComparingTo("0.00");

        orderClient.set(order(
                "A000642",
                "Выдан",
                true,
                Instant.parse("2026-08-06T11:01:00Z"),
                Instant.parse("2026-08-06T15:30:00Z"),
                "6500.00"
        ));
        OrderSyncResult stale = orderSyncService.synchronize(period());

        assertThat(stale.recordsSkipped()).isEqualTo(1);
        assertThat(documentDeleted()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT source_updated_at FROM sales_documents",
                Instant.class
        )).isEqualTo(Instant.parse("2026-08-06T16:00:00Z"));
    }

    @Test
    void webhookRefreshesOnlyTargetOrderIdempotently() {
        orderClient.set(order(
                "A000702",
                "Выдан",
                true,
                Instant.parse("2026-08-07T11:01:00Z"),
                Instant.parse("2026-08-07T12:00:00Z"),
                "7000.00"
        ));

        OrderSyncResult created =
                orderSyncService.synchronizeWebhookOrder("order-1");
        OrderSyncResult unchanged =
                orderSyncService.synchronizeWebhookOrder("order-1");

        assertThat(created.recordsCreated()).isEqualTo(1);
        assertThat(unchanged.recordsSkipped()).isEqualTo(1);
        assertThat(orderClient.listRequestCount()).isZero();
        assertThat(documentDeleted()).isFalse();

        orderClient.set(order(
                "A000702",
                "Возвращен",
                true,
                Instant.parse("2026-08-07T11:01:00Z"),
                Instant.parse("2026-08-07T13:00:00Z"),
                "7000.00"
        ));

        OrderSyncResult removed =
                orderSyncService.synchronizeWebhookOrder("order-1");

        assertThat(removed.documentsDeleted()).isEqualTo(1);
        assertThat(documentDeleted()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM sync_runs
                WHERE sync_scope = 'ORDERS'
                  AND trigger_type = 'REPROCESS'
                  AND status = 'SUCCESS'
                """,
                Integer.class
        )).isEqualTo(3);
    }

    @Test
    void serializesConcurrentWebhookRefreshesForTheSameOrder() {
        orderClient.set(order(
                "A000703",
                "Выдан",
                true,
                Instant.parse("2026-08-07T11:01:00Z"),
                Instant.parse("2026-08-07T12:00:00Z"),
                "7000.00"
        ));
        orderClient.awaitTwoTargetedDetails();

        CompletableFuture<OrderSyncResult> first = CompletableFuture
                .supplyAsync(() -> orderSyncService
                        .synchronizeWebhookOrder("order-1"));
        CompletableFuture<OrderSyncResult> second = CompletableFuture
                .supplyAsync(() -> orderSyncService
                        .synchronizeWebhookOrder("order-1"));
        List<OrderSyncResult> results = List.of(first.join(), second.join());

        assertThat(results)
                .extracting(OrderSyncResult::recordsCreated)
                .containsExactlyInAnyOrder(1, 0);
        assertThat(results)
                .extracting(OrderSyncResult::recordsSkipped)
                .containsExactlyInAnyOrder(0, 1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sales_documents WHERE is_deleted = false",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sales_document_items",
                Integer.class
        )).isEqualTo(1);
    }

    @Test
    void retriesWhenOrderChangesBetweenListAndDetailRequests() {
        orderClient.set(order(
                "A000701", "Выдан", true,
                Instant.parse("2026-08-07T11:01:00Z"),
                Instant.parse("2026-08-07T12:00:00Z"), "7000.00"
        ));
        orderClient.changeNumberBeforeDetail();

        assertThatThrownBy(() -> orderSyncService.synchronize(period()))
                .isInstanceOf(OrderSyncException.class)
                .hasCauseInstanceOf(LiveSkladOrderChangedException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sales_documents", Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT is_retryable FROM sync_run_errors
                WHERE stage = 'ORDER_SYNC' ORDER BY created_at DESC LIMIT 1
                """, Boolean.class
        )).isTrue();
    }

    private OrderFixture order(
            String number,
            String status,
            boolean visible,
            Instant occurredAt,
            Instant updatedAt,
            String amount
    ) {
        return new OrderFixture(number, status, visible, occurredAt, updatedAt, amount);
    }

    private OrderSyncPeriod period() {
        return new OrderSyncPeriod(
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-08-10T00:00:00Z")
        );
    }

    private java.util.UUID storeId() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM stores WHERE external_id = 'store-1'",
                java.util.UUID.class
        );
    }

    private boolean documentDeleted() {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT is_deleted FROM sales_documents",
                Boolean.class
        ));
    }

    private BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2);
    }

    private record OrderFixture(
            String number,
            String status,
            boolean visible,
            Instant occurredAt,
            Instant updatedAt,
            String amount
    ) {
    }

    @TestConfiguration
    static class FakeClientConfiguration {

        @Bean
        @Primary
        FakeLiveSkladClient fakeLiveSkladClient(ObjectMapper objectMapper) {
            return new FakeLiveSkladClient(objectMapper);
        }

        @Bean
        @Primary
        FakeLiveSkladOrderClient fakeLiveSkladOrderClient(
                ObjectMapper objectMapper
        ) {
            return new FakeLiveSkladOrderClient(objectMapper);
        }
    }

    static final class FakeLiveSkladClient implements LiveSkladClient {

        private final ObjectMapper objectMapper;

        FakeLiveSkladClient(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public List<LiveSkladStorePayload> fetchStores() {
            return List.of(new LiveSkladStorePayload(
                    "store-1",
                    "Fixture Store",
                    "Fixture Address",
                    "#123456",
                    raw("store-1")
            ));
        }

        @Override
        public List<LiveSkladEmployeePayload> fetchEmployees(String storeExternalId) {
            return List.of(new LiveSkladEmployeePayload(
                    "employee-kirill",
                    "Кирилл ДОЛГОВ",
                    raw("employee-kirill")
            ));
        }

        @Override
        public List<LiveSkladSaleSummaryPayload> fetchSales(
                String storeExternalId,
                Instant periodStart,
                Instant periodEnd
        ) {
            return List.of();
        }

        @Override
        public LiveSkladSaleDetailPayload fetchSaleDetail(String saleExternalId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<LiveSkladCashItemPayload> fetchCashItems() {
            return List.of();
        }

        @Override
        public List<LiveSkladCashRegisterPayload> fetchCashRegisters(
                String storeExternalId
        ) {
            return List.of();
        }

        @Override
        public List<LiveSkladCashTransactionPayload> fetchCashTransactions(
                String cashRegisterExternalId,
                String cashItemExternalId,
                Instant periodStart,
                Instant periodEnd
        ) {
            return List.of();
        }

        @Override
        public LiveSkladReturnDetailPayload fetchReturnDetail(
                String returnExternalId
        ) {
            throw new UnsupportedOperationException();
        }

        private JsonNode raw(String id) {
            return objectMapper.createObjectNode().put("id", id).put("name", id);
        }
    }

    static final class FakeLiveSkladOrderClient implements LiveSkladOrderClient {

        private final ObjectMapper objectMapper;
        private OrderFixture fixture;
        private boolean changeNumberBeforeDetail;
        private int listRequestCount;

        private volatile CyclicBarrier detailBarrier;

        FakeLiveSkladOrderClient(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        void set(OrderFixture fixture) {
            this.fixture = fixture;
        }

        void changeNumberBeforeDetail() {
            changeNumberBeforeDetail = true;
        }

        void awaitTwoTargetedDetails() {
            detailBarrier = new CyclicBarrier(2);
        }

        void clear() {
            fixture = null;
            changeNumberBeforeDetail = false;
            listRequestCount = 0;
            detailBarrier = null;
        }

        int listRequestCount() {
            return listRequestCount;
        }

        @Override
        public List<LiveSkladOrderSummaryPayload> fetchOrders(
                Instant changedPeriodStart,
                Instant changedPeriodEnd
        ) {
            listRequestCount++;
            if (fixture == null) {
                return List.of();
            }
            return List.of(new LiveSkladOrderSummaryPayload(
                    "order-1",
                    fixture.number(),
                    fixture.occurredAt().minusSeconds(86400),
                    fixture.visible(),
                    "status-1",
                    fixture.status(),
                    "store-1",
                    raw("order-1")
            ));
        }

        @Override
        public LiveSkladOrderDetailPayload fetchOrderDetail(
                String orderExternalId
        ) {
            CyclicBarrier barrier = detailBarrier;
            if (barrier != null) {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "Interrupted while coordinating order detail race",
                            exception
                    );
                } catch (BrokenBarrierException | TimeoutException exception) {
                    throw new IllegalStateException(
                            "Failed to coordinate order detail race", exception
                    );
                }
            }
            LiveSkladOrderPositionPayload position =
                    new LiveSkladOrderPositionPayload(
                            "position-1",
                            "product-work-1",
                            "WORK-1",
                            null,
                            "Замена аккумулятора",
                            true,
                            new BigDecimal("1.000"),
                            new BigDecimal(fixture.amount()),
                            new BigDecimal(fixture.amount()),
                            new BigDecimal("15000.00"),
                            fixture.occurredAt(),
                            "employee-kirill",
                            "Кирилл ДОЛГОВ",
                            raw("position-1")
                    );
            return new LiveSkladOrderDetailPayload(
                    orderExternalId,
                    changeNumberBeforeDetail
                            ? fixture.number() + "-changed"
                            : fixture.number(),
                    fixture.occurredAt().minusSeconds(86400),
                    fixture.updatedAt(),
                    fixture.occurredAt(),
                    fixture.visible(),
                    "status-1",
                    fixture.status(),
                    "store-1",
                    List.of(position),
                    raw(orderExternalId)
            );
        }

        private JsonNode raw(String id) {
            return objectMapper.createObjectNode().put("id", id);
        }
    }
}
