package com.storeanalytics.sync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.storeanalytics.integration.livesklad.client.LiveSkladClient;
import com.storeanalytics.integration.livesklad.dto.LiveSkladCashItemPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladCashRegisterPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladCashTransactionPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladEmployeePayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladReturnDetailPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladReturnPositionPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladSaleDetailPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladSalePositionPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladSaleSummaryPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladStorePayload;
import com.storeanalytics.integration.livesklad.exception.LiveSkladException;
import com.storeanalytics.sync.exception.ReturnSyncException;
import com.storeanalytics.quality.model.DataQualityStatus;
import com.storeanalytics.sync.model.NormalizationStatus;
import com.storeanalytics.sync.model.SyncStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Import(ReturnSyncIntegrationTest.FakeClientConfiguration.class)
class ReturnSyncIntegrationTest {

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
    private ReturnSyncService returnSyncService;
    @Autowired
    private FakeLiveSkladClient fakeClient;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void cleanDatabaseAndResetSource() {
        jdbcTemplate.update("DELETE FROM data_quality_issues");
        jdbcTemplate.update("DELETE FROM sales_payments");
        jdbcTemplate.update("DELETE FROM sales_document_items");
        jdbcTemplate.update("DELETE FROM sales_documents");
        jdbcTemplate.update("DELETE FROM product_category_assignments");
        jdbcTemplate.update("DELETE FROM raw_record_versions");
        jdbcTemplate.update("DELETE FROM sync_run_errors");
        jdbcTemplate.update("DELETE FROM sync_runs");
        jdbcTemplate.update("DELETE FROM products");
        jdbcTemplate.update("DELETE FROM cash_registers");
        jdbcTemplate.update("DELETE FROM employee_store_assignments");
        jdbcTemplate.update("DELETE FROM employees");
        jdbcTemplate.update("DELETE FROM stores");
        fakeClient.reset(storePayload(), employeePayload());
    }

    @Test
    void synchronizesSnapshotsDeletionAndReactivationIdempotently() {
        bootstrapReferences();
        SaleFixture sale = new SaleFixture(
                "sale-return-source",
                "sale-position-source",
                "product-return",
                Instant.parse("2026-07-01T10:00:00Z"),
                "100.00",
                "60.00"
        );
        seedSale(sale);
        ReturnFixture source = new ReturnFixture(
                "return-1",
                sale,
                Instant.parse("2026-07-01T12:00:00Z"),
                Instant.parse("2026-07-01T12:02:00Z"),
                "saleReturn"
        );
        configureReturn(source);
        ReturnSyncPeriod period = period();

        ReturnSyncResult first = returnSyncService.synchronize(period);
        ReturnSyncResult unchanged = returnSyncService.synchronize(period);

        assertThat(first.status()).isEqualTo(SyncStatus.SUCCESS);
        assertThat(first.recordsCreated()).isEqualTo(1);
        assertThat(first.registersCreated()).isEqualTo(1);
        assertThat(first.itemsCreated()).isEqualTo(1);
        assertThat(first.paymentsCreated()).isEqualTo(1);
        assertThat(unchanged.recordsSkipped()).isEqualTo(1);
        Map<String, Object> document = jdbcTemplate.queryForMap(
                """
                SELECT returned.document_kind,
                       returned.employee_id = original.employee_id AS same_employee,
                       attributed_employee.external_id AS attributed_employee,
                       returned.original_document_id = original.id AS linked
                FROM sales_documents returned
                JOIN sales_documents original
                  ON original.external_id = 'sale-return-source'
                JOIN employees attributed_employee
                  ON attributed_employee.id = returned.employee_id
                WHERE returned.external_id = 'return-1'
                """
        );
        assertThat(document.get("document_kind")).isEqualTo("RETURN");
        assertThat(document.get("same_employee")).isEqualTo(true);
        assertThat(document.get("attributed_employee")).isEqualTo("employee-1");
        assertThat(document.get("linked")).isEqualTo(true);
        Map<String, Object> item = jdbcTemplate.queryForMap(
                """
                SELECT returned.original_item_id = original.id AS linked,
                       returned.analytics_category_id =
                           original.analytics_category_id AS same_category
                FROM sales_document_items returned
                JOIN sales_document_items original
                  ON original.external_id = 'sale-position-source'
                WHERE returned.external_id = 'return-position'
                """
        );
        assertThat(item.get("linked")).isEqualTo(true);
        assertThat(item.get("same_category")).isEqualTo(true);

        configureReturn(new ReturnFixture(
                source.externalId(),
                sale,
                source.occurredAt(),
                Instant.parse("2026-07-01T12:01:00Z"),
                "delete"
        ));
        ReturnSyncResult staleDelete =
                returnSyncService.synchronize(period);
        assertThat(staleDelete.documentsDeleted()).isZero();
        assertThat(staleDelete.recordsSkipped()).isEqualTo(1);
        assertReturnDeleted(false);

        configureReturn(new ReturnFixture(
                source.externalId(),
                sale,
                source.occurredAt(),
                Instant.parse("2026-07-01T12:03:00Z"),
                "delete"
        ));
        ReturnSyncResult deleted = returnSyncService.synchronize(period);
        assertThat(deleted.documentsDeleted()).isEqualTo(1);
        assertReturnDeleted(true);

        configureReturn(new ReturnFixture(
                source.externalId(),
                sale,
                source.occurredAt(),
                Instant.parse("2026-07-01T12:04:00Z"),
                "saleReturn"
        ));
        ReturnSyncResult reactivated = returnSyncService.synchronize(period);
        assertThat(reactivated.recordsUpdated()).isEqualTo(1);
        assertReturnDeleted(false);
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM raw_record_versions
                WHERE entity_type = 'RETURN_DOCUMENT'
                """,
                Integer.class
        )).isEqualTo(4);
    }

    @Test
    void retriesSkippedRawVersionAfterOriginalSaleArrives() {
        bootstrapReferences();
        SaleFixture lateSale = new SaleFixture(
                "sale-late",
                "sale-position-late",
                "product-late",
                Instant.parse("2026-07-01T10:00:00Z"),
                "50.00",
                "20.00"
        );
        ReturnFixture source = new ReturnFixture(
                "return-late",
                lateSale,
                Instant.parse("2026-07-01T12:00:00Z"),
                Instant.parse("2026-07-01T12:02:00Z"),
                "saleReturn"
        );
        configureReturn(source);

        ReturnSyncResult unresolved =
                returnSyncService.synchronize(period());

        assertThat(unresolved.status()).isEqualTo(SyncStatus.PARTIAL_SUCCESS);
        assertThat(unresolved.unresolvedDocuments()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT normalization_status FROM raw_record_versions
                WHERE entity_type = 'RETURN_DOCUMENT'
                """,
                String.class
        )).isEqualTo(NormalizationStatus.SKIPPED.name());
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT status FROM data_quality_issues
                WHERE issue_code = 'RETURN_ORIGINAL_DOCUMENT_MISSING'
                """,
                String.class
        )).isEqualTo(DataQualityStatus.OPEN.name());

        seedSale(lateSale);
        ReturnSyncResult recovered =
                returnSyncService.synchronize(period());

        assertThat(recovered.status()).isEqualTo(SyncStatus.SUCCESS);
        assertThat(recovered.recordsCreated()).isEqualTo(1);
        assertThat(recovered.qualityIssuesResolved()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT normalization_status FROM raw_record_versions
                WHERE entity_type = 'RETURN_DOCUMENT'
                """,
                String.class
        )).isEqualTo(NormalizationStatus.NORMALIZED.name());
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT status FROM data_quality_issues
                WHERE issue_code = 'RETURN_ORIGINAL_DOCUMENT_MISSING'
                """,
                String.class
        )).isEqualTo(DataQualityStatus.RESOLVED.name());
    }

    @Test
    void detailFailureDoesNotPersistRegistersOrRawFacts() {
        storeSyncService.synchronize();
        SaleFixture missingSale = new SaleFixture(
                "sale-missing",
                "sale-position-missing",
                "product-failure",
                Instant.parse("2026-07-01T10:00:00Z"),
                "50.00",
                "20.00"
        );
        ReturnFixture source = new ReturnFixture(
                "return-failure",
                missingSale,
                Instant.parse("2026-07-01T12:00:00Z"),
                Instant.parse("2026-07-01T12:02:00Z"),
                "saleReturn"
        );
        configureReturn(source);
        fakeClient.failReturnDetail(
                source.externalId(),
                new LiveSkladException("sensitive upstream response")
        );

        assertThatThrownBy(() -> returnSyncService.synchronize(period()))
                .isInstanceOf(ReturnSyncException.class)
                .hasMessage("Return synchronization failed");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM cash_registers",
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM raw_record_versions
                WHERE entity_type IN (
                    'CASH_ITEM_DICTIONARY',
                    'CASH_REGISTER',
                    'RETURN_DOCUMENT'
                )
                """,
                Integer.class
        )).isZero();
        Map<String, Object> failure = jdbcTemplate.queryForMap(
                """
                SELECT run.status, error.is_retryable
                FROM sync_runs run
                JOIN sync_run_errors error ON error.sync_run_id = run.id
                WHERE run.sync_scope = 'RETURNS'
                """
        );
        assertThat(failure.get("status")).isEqualTo(SyncStatus.FAILED.name());
        assertThat(failure.get("is_retryable")).isEqualTo(true);
    }

    private void bootstrapReferences() {
        storeSyncService.synchronize();
        employeeSyncService.synchronize();
    }

    private void seedSale(SaleFixture fixture) {
        LiveSkladSalePositionPayload position = new LiveSkladSalePositionPayload(
                fixture.positionExternalId(),
                fixture.productExternalId(),
                "CODE",
                "SKU",
                "Fixture Product",
                false,
                BigDecimal.ONE.setScale(3),
                money(fixture.netAmount()),
                money(fixture.netAmount()),
                money(fixture.costAmount())
        );
        LiveSkladSaleSummaryPayload summary = new LiveSkladSaleSummaryPayload(
                fixture.externalId(),
                "S-1",
                fixture.occurredAt(),
                "sale",
                money(fixture.netAmount()),
                money(fixture.netAmount()),
                money(fixture.costAmount()),
                rawSaleSummary(fixture)
        );
        LiveSkladSaleDetailPayload detail = new LiveSkladSaleDetailPayload(
                fixture.externalId(),
                "S-1",
                fixture.occurredAt(),
                fixture.occurredAt().plusSeconds(60),
                "sale",
                "store-1",
                "employee-1",
                "Fixture Employee",
                money(fixture.netAmount()),
                BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(2),
                List.of(position),
                rawSaleDetail(fixture, position)
        );
        fakeClient.setSales(
                Map.of("store-1", List.of(summary)),
                Map.of(fixture.externalId(), detail)
        );
        ReturnSyncPeriod returnPeriod = period();
        salesSyncService.synchronize(new SalesSyncPeriod(
                returnPeriod.start(),
                returnPeriod.end()
        ));
    }

    private void configureReturn(ReturnFixture fixture) {
        LiveSkladCashItemPayload cashItem = cashItem();
        LiveSkladCashRegisterPayload register = cashRegister();
        LiveSkladCashTransactionPayload transaction =
                cashTransaction(fixture);
        LiveSkladReturnDetailPayload detail = returnDetail(fixture);
        fakeClient.setReturns(
                List.of(cashItem),
                Map.of("store-1", List.of(register)),
                List.of(transaction),
                Map.of(fixture.externalId(), detail)
        );
    }

    private ReturnSyncPeriod period() {
        return new ReturnSyncPeriod(
                Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-07-02T00:00:00Z")
        );
    }

    private void assertReturnDeleted(boolean expected) {
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT is_deleted FROM sales_documents
                WHERE external_id = 'return-1'
                """,
                Boolean.class
        )).isEqualTo(expected);
    }

    private List<LiveSkladStorePayload> storePayload() {
        return List.of(new LiveSkladStorePayload(
                "store-1",
                "Fixture Store",
                "Fixture Address",
                "#123456",
                raw("store-1", "store")
        ));
    }

    private Map<String, List<LiveSkladEmployeePayload>> employeePayload() {
        return Map.of("store-1", List.of(new LiveSkladEmployeePayload(
                "employee-1",
                "Fixture Employee",
                raw("employee-1", "employee")
        )));
    }

    private LiveSkladCashItemPayload cashItem() {
        return new LiveSkladCashItemPayload(
                "cash-item-return",
                "Sale return",
                "saleReturn",
                false,
                true,
                raw("cash-item-return", "saleReturn")
        );
    }

    private LiveSkladCashRegisterPayload cashRegister() {
        return new LiveSkladCashRegisterPayload(
                "register-1",
                "Fixture Register",
                "store-1",
                raw("register-1", "cashRegister")
        );
    }

    private LiveSkladCashTransactionPayload cashTransaction(
            ReturnFixture fixture
    ) {
        return new LiveSkladCashTransactionPayload(
                "cash-" + fixture.externalId(),
                fixture.occurredAt(),
                fixture.sourceUpdatedAt(),
                fixture.sourceType(),
                "store-1",
                "register-1",
                "cash-item-return",
                "saleReturn",
                false,
                true,
                false,
                money(fixture.sale().netAmount()),
                "employee-1",
                null,
                fixture.externalId(),
                returnRaw("cash-" + fixture.externalId(), fixture)
        );
    }

    private LiveSkladReturnDetailPayload returnDetail(
            ReturnFixture fixture
    ) {
        LiveSkladReturnPositionPayload position =
                new LiveSkladReturnPositionPayload(
                        "return-position",
                        fixture.sale().positionExternalId(),
                        fixture.sale().productExternalId(),
                        "CODE",
                        "SKU",
                        "Fixture Product",
                        false,
                        BigDecimal.ONE.setScale(3),
                        money(fixture.sale().netAmount()),
                        money(fixture.sale().netAmount()),
                        money(fixture.sale().costAmount())
                );
        return new LiveSkladReturnDetailPayload(
                fixture.externalId(),
                "R-1",
                fixture.occurredAt(),
                fixture.sourceUpdatedAt(),
                "saleReturn",
                "store-1",
                "return-processor",
                fixture.sale().externalId(),
                money(fixture.sale().netAmount()),
                BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(2),
                List.of(position),
                returnRaw(fixture.externalId(), fixture)
        );
    }

    private ObjectNode rawSaleSummary(SaleFixture fixture) {
        return raw(fixture.externalId(), "sale");
    }

    private ObjectNode rawSaleDetail(
            SaleFixture fixture,
            LiveSkladSalePositionPayload position
    ) {
        ObjectNode raw = raw(fixture.externalId(), "sale");
        raw.put("positionId", position.externalId());
        return raw;
    }

    private ObjectNode returnRaw(
            String externalId,
            ReturnFixture fixture
    ) {
        ObjectNode raw = raw(externalId, fixture.sourceType());
        raw.put("dateChange", fixture.sourceUpdatedAt().toString());
        return raw;
    }

    private ObjectNode raw(String externalId, String type) {
        ObjectNode raw = objectMapper.createObjectNode();
        raw.put("id", externalId);
        raw.put("type", type);
        return raw;
    }

    private BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    private record SaleFixture(
            String externalId,
            String positionExternalId,
            String productExternalId,
            Instant occurredAt,
            String netAmount,
            String costAmount
    ) {
    }

    private record ReturnFixture(
            String externalId,
            SaleFixture sale,
            Instant occurredAt,
            Instant sourceUpdatedAt,
            String sourceType
    ) {
    }

    @TestConfiguration
    static class FakeClientConfiguration {

        @Bean
        @Primary
        FakeLiveSkladClient fakeLiveSkladClient() {
            return new FakeLiveSkladClient();
        }
    }

    static class FakeLiveSkladClient implements LiveSkladClient {

        private List<LiveSkladStorePayload> stores = List.of();
        private Map<String, List<LiveSkladEmployeePayload>> employees = Map.of();
        private Map<String, List<LiveSkladSaleSummaryPayload>> sales = Map.of();
        private Map<String, LiveSkladSaleDetailPayload> saleDetails = Map.of();
        private List<LiveSkladCashItemPayload> cashItems = List.of();
        private Map<String, List<LiveSkladCashRegisterPayload>> registers =
                Map.of();
        private List<LiveSkladCashTransactionPayload> transactions = List.of();
        private Map<String, LiveSkladReturnDetailPayload> returnDetails =
                Map.of();
        private String failingReturnId;
        private RuntimeException returnFailure;

        void reset(
                List<LiveSkladStorePayload> sourceStores,
                Map<String, List<LiveSkladEmployeePayload>> sourceEmployees
        ) {
            stores = List.copyOf(sourceStores);
            employees = Map.copyOf(sourceEmployees);
            sales = Map.of();
            saleDetails = Map.of();
            cashItems = List.of();
            registers = Map.of();
            transactions = List.of();
            returnDetails = Map.of();
            failingReturnId = null;
            returnFailure = null;
        }

        void setSales(
                Map<String, List<LiveSkladSaleSummaryPayload>> sourceSales,
                Map<String, LiveSkladSaleDetailPayload> sourceDetails
        ) {
            sales = Map.copyOf(sourceSales);
            saleDetails = Map.copyOf(sourceDetails);
        }

        void setReturns(
                List<LiveSkladCashItemPayload> sourceCashItems,
                Map<String, List<LiveSkladCashRegisterPayload>> sourceRegisters,
                List<LiveSkladCashTransactionPayload> sourceTransactions,
                Map<String, LiveSkladReturnDetailPayload> sourceDetails
        ) {
            cashItems = List.copyOf(sourceCashItems);
            registers = Map.copyOf(sourceRegisters);
            transactions = List.copyOf(sourceTransactions);
            returnDetails = Map.copyOf(sourceDetails);
            failingReturnId = null;
            returnFailure = null;
        }

        void failReturnDetail(
                String returnExternalId,
                RuntimeException failure
        ) {
            failingReturnId = returnExternalId;
            returnFailure = failure;
        }

        @Override
        public List<LiveSkladStorePayload> fetchStores() {
            return stores;
        }

        @Override
        public List<LiveSkladEmployeePayload> fetchEmployees(
                String storeExternalId
        ) {
            return employees.getOrDefault(storeExternalId, List.of());
        }

        @Override
        public List<LiveSkladSaleSummaryPayload> fetchSales(
                String storeExternalId,
                Instant periodStart,
                Instant periodEnd
        ) {
            return sales.getOrDefault(storeExternalId, List.of());
        }

        @Override
        public LiveSkladSaleDetailPayload fetchSaleDetail(
                String saleExternalId
        ) {
            return saleDetails.get(saleExternalId);
        }

        @Override
        public List<LiveSkladCashItemPayload> fetchCashItems() {
            return cashItems;
        }

        @Override
        public List<LiveSkladCashRegisterPayload> fetchCashRegisters(
                String storeExternalId
        ) {
            return registers.getOrDefault(storeExternalId, List.of());
        }

        @Override
        public List<LiveSkladCashTransactionPayload> fetchCashTransactions(
                String cashRegisterExternalId,
                String cashItemExternalId,
                Instant periodStart,
                Instant periodEnd
        ) {
            return transactions;
        }

        @Override
        public LiveSkladReturnDetailPayload fetchReturnDetail(
                String returnExternalId
        ) {
            if (returnExternalId.equals(failingReturnId)) {
                throw returnFailure;
            }
            return returnDetails.get(returnExternalId);
        }
    }
}
