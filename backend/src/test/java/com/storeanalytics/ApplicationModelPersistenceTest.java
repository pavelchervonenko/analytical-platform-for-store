package com.storeanalytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.audit.model.AuditLog;
import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.model.UserRole;
import com.storeanalytics.auth.model.UserStoreAccess;
import com.storeanalytics.employee.model.Employee;
import com.storeanalytics.employee.model.EmployeeStoreAssignment;
import com.storeanalytics.employee.model.EmployeeStoreAssignmentId;
import com.storeanalytics.integration.connection.model.IntegrationConnection;
import com.storeanalytics.metrics.model.ReportContent;
import com.storeanalytics.metrics.model.ReportDefinition;
import com.storeanalytics.metrics.model.ReportPeriodType;
import com.storeanalytics.metrics.model.ReportSnapshot;
import com.storeanalytics.metrics.model.ReportStatus;
import com.storeanalytics.product.model.AnalyticsCategory;
import com.storeanalytics.product.model.AnalyticsCategoryKind;
import com.storeanalytics.product.model.AnalyticsCategoryRules;
import com.storeanalytics.product.model.CategoryAssignmentDetails;
import com.storeanalytics.product.model.CategoryAssignmentSource;
import com.storeanalytics.product.model.DeviceFamily;
import com.storeanalytics.product.model.InventoryValues;
import com.storeanalytics.product.model.Product;
import com.storeanalytics.product.model.ProductCategoryAssignment;
import com.storeanalytics.product.model.ProductConditionType;
import com.storeanalytics.product.model.ProductDetails;
import com.storeanalytics.product.model.ProductSourceKind;
import com.storeanalytics.product.model.SourceProductGroup;
import com.storeanalytics.product.model.StoreProductInventory;
import com.storeanalytics.product.model.StoreProductInventoryHistory;
import com.storeanalytics.quality.model.DataQualityIssue;
import com.storeanalytics.quality.model.DataQualitySeverity;
import com.storeanalytics.sales.model.CostQuality;
import com.storeanalytics.sales.model.PaymentMethod;
import com.storeanalytics.sales.model.SalesDocument;
import com.storeanalytics.sales.model.SalesDocumentAmounts;
import com.storeanalytics.sales.model.SalesDocumentDetails;
import com.storeanalytics.sales.model.SalesDocumentIdentity;
import com.storeanalytics.sales.model.SalesDocumentItem;
import com.storeanalytics.sales.model.SalesDocumentKind;
import com.storeanalytics.sales.model.SalesItemAmounts;
import com.storeanalytics.sales.model.SalesItemClassification;
import com.storeanalytics.sales.model.SalesItemIdentity;
import com.storeanalytics.sales.model.SalesPayment;
import com.storeanalytics.store.model.CashRegister;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.sync.model.RawRecordVersion;
import com.storeanalytics.sync.model.SourceSystem;
import com.storeanalytics.sync.model.SyncJob;
import com.storeanalytics.sync.model.SyncJobDefinition;
import com.storeanalytics.sync.model.SyncJobType;
import com.storeanalytics.sync.model.SyncRun;
import com.storeanalytics.sync.model.SyncRunError;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.metamodel.EntityType;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.UUID;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ApplicationModelPersistenceTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    @Transactional
    void applicationApisPersistCompleteModelGraph() {
        Instant now = Instant.parse("2026-07-16T12:00:00Z");
        LocalDate businessDate = LocalDate.of(2026, 7, 16);
        ModelGraph graph = createBaseGraph(now);

        persistRelationships(graph, now, businessDate);
        persistFactsAndOperations(graph, now, businessDate);
        updateMutableEntities(graph, now);
        graph.syncRun.complete(1, 1, 0, 0, now.plusSeconds(1));
        entityManager.flush();

        assertDbManagedTimestampsAreSynchronized(graph);
        entityManager.clear();
        assertEveryEntityWasPersisted();
    }

    @Test
    void optimisticVersionRejectsLostUpdate() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        UUID storeId = transaction.execute(status -> {
            IntegrationConnection connection = new IntegrationConnection(
                    "optimistic-lock-test",
                    SourceSystem.LIVESKLAD,
                    "Optimistic lock test",
                    "https://example.invalid",
                    "env:TEST_CREDENTIALS"
            );
            entityManager.persist(connection);
            Store store = Store.fromLiveSklad(
                    connection,
                    "optimistic-store-test",
                    "Original name",
                    "Original address"
            );
            entityManager.persist(store);
            entityManager.flush();
            return store.getId();
        });

        Store firstCopy = transaction.execute(status -> entityManager.find(Store.class, storeId));
        Store staleCopy = transaction.execute(status -> entityManager.find(Store.class, storeId));

        transaction.executeWithoutResult(status -> {
            firstCopy.updateFromLiveSklad("First update", "First address");
            entityManager.merge(firstCopy);
            entityManager.flush();
        });

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            staleCopy.updateFromLiveSklad("Lost update", "Lost address");
            entityManager.merge(staleCopy);
            entityManager.flush();
        })).isInstanceOf(OptimisticLockException.class);
    }

    @Test
    void employeeAssignmentRejectsLostUpdate() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        EmployeeStoreAssignmentId assignmentId = transaction.execute(status -> {
            IntegrationConnection connection = new IntegrationConnection(
                    "employee-assignment-lock-test",
                    SourceSystem.LIVESKLAD,
                    "Employee assignment lock test",
                    "https://example.invalid",
                    "env:TEST_CREDENTIALS"
            );
            entityManager.persist(connection);
            Store store = Store.fromLiveSklad(
                    connection,
                    "employee-assignment-store",
                    "Employee assignment store",
                    "Address"
            );
            Employee employee = Employee.fromLiveSklad(
                    connection,
                    "employee-assignment-employee",
                    "Employee",
                    Instant.parse("2026-07-16T12:00:00Z")
            );
            entityManager.persist(store);
            entityManager.persist(employee);
            entityManager.flush();

            EmployeeStoreAssignment assignment =
                    new EmployeeStoreAssignment(employee, store, true);
            entityManager.persist(assignment);
            entityManager.flush();
            return assignment.getId();
        });

        EmployeeStoreAssignment firstCopy = transaction.execute(
                status -> entityManager.find(EmployeeStoreAssignment.class, assignmentId)
        );
        EmployeeStoreAssignment staleCopy = transaction.execute(
                status -> entityManager.find(EmployeeStoreAssignment.class, assignmentId)
        );

        transaction.executeWithoutResult(status -> {
            firstCopy.update(false, false);
            entityManager.merge(firstCopy);
            entityManager.flush();
        });

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            staleCopy.update(false, true);
            entityManager.merge(staleCopy);
            entityManager.flush();
        })).isInstanceOf(OptimisticLockException.class);
    }


    private ModelGraph createBaseGraph(Instant now) {
        ModelGraph graph = new ModelGraph();
        graph.connection = new IntegrationConnection(
                "model-persistence-test",
                SourceSystem.LIVESKLAD,
                "Model persistence test",
                "https://example.invalid",
                "env:TEST_CREDENTIALS"
        );
        entityManager.persist(graph.connection);

        graph.store = Store.fromLiveSklad(
                graph.connection, "store-model-test", "Model Test Store", "Test address"
        );
        graph.user = new AppUser(
                "model-test@example.invalid", "password-hash", "Model Test", UserRole.ADMIN
        );
        graph.syncJob = SyncJob.create(
                new SyncJobDefinition(
                        graph.connection,
                        graph.user,
                        SyncJobType.BACKFILL,
                        now,
                        now.plus(Duration.ofDays(1)),
                        Duration.ofDays(1),
                        5
                ),
                now
        );
        graph.syncRun = SyncRun.startStoreSync(graph.connection, now);
        graph.employee = Employee.fromLiveSklad(
                graph.connection, "employee-model-test", "Model Employee", now
        );
        CashRegister cashRegister = CashRegister.fromLiveSklad(
                graph.connection, graph.store, "cash-model-test", "Model Cash Register"
        );
        graph.sourceGroup = SourceProductGroup.fromLiveSklad(
                graph.connection, "group-model-test", "/MODEL", "Model Group", null
        );
        graph.product = Product.fromLiveSklad(
                graph.connection,
                "product-model-test",
                new ProductDetails(
                        graph.sourceGroup,
                        "MODEL-CODE",
                        "MODEL-SKU",
                        "Model Product",
                        ProductSourceKind.PRODUCT,
                        now
                )
        );
        graph.category = new AnalyticsCategory(
                "MODEL_DEVICE",
                "Model Device",
                null,
                new AnalyticsCategoryRules(
                        AnalyticsCategoryKind.DEVICE,
                        DeviceFamily.IPHONE,
                        true,
                        true,
                        false,
                        null,
                        false
                )
        );

        entityManager.persist(graph.store);
        entityManager.persist(graph.user);
        entityManager.persist(graph.syncJob);
        entityManager.persist(graph.syncRun);
        entityManager.persist(graph.employee);
        entityManager.persist(cashRegister);
        entityManager.persist(graph.sourceGroup);
        entityManager.persist(graph.product);
        entityManager.persist(graph.category);
        entityManager.flush();
        return graph;
    }

    private void persistRelationships(
            ModelGraph graph,
            Instant now,
            LocalDate businessDate
    ) {
        graph.userStoreAccess =
                new UserStoreAccess(graph.user, graph.store, graph.user);
        graph.employeeStoreAssignment =
                new EmployeeStoreAssignment(graph.employee, graph.store, true);
        graph.rawRecord = RawRecordVersion.pendingStore(
                "store-model-test",
                "{\"id\":\"store-model-test\"}",
                "a".repeat(64),
                graph.syncRun,
                now
        );
        graph.categoryAssignment = new ProductCategoryAssignment(
                graph.product,
                graph.category,
                new CategoryAssignmentDetails(
                        ProductConditionType.NEW,
                        CategoryAssignmentSource.MANUAL,
                        "model-v1",
                        now,
                        null,
                        graph.user,
                        "Persistence test"
                )
        );
        InventoryValues inventoryValues = new InventoryValues(
                BigDecimal.ONE,
                new BigDecimal("100.00"),
                new BigDecimal("60.00"),
                now
        );
        graph.inventory = new StoreProductInventory(
                graph.store, graph.product, inventoryValues, graph.syncRun
        );
        StoreProductInventoryHistory inventoryHistory = new StoreProductInventoryHistory(
                graph.store, graph.product, inventoryValues, now, graph.syncRun
        );
        graph.salesDocument = createSalesDocument(graph, now, businessDate);

        entityManager.persist(graph.userStoreAccess);
        entityManager.persist(graph.employeeStoreAssignment);
        entityManager.persist(graph.rawRecord);
        entityManager.persist(graph.categoryAssignment);
        entityManager.persist(graph.inventory);
        entityManager.persist(inventoryHistory);
        entityManager.persist(graph.salesDocument);
    }

    private SalesDocument createSalesDocument(
            ModelGraph graph,
            Instant now,
            LocalDate businessDate
    ) {
        return new SalesDocument(
                new SalesDocumentIdentity(
                        graph.connection,
                        SourceSystem.LIVESKLAD,
                        "sale-model-test",
                        graph.store,
                        graph.employee,
                        null,
                        graph.syncRun
                ),
                new SalesDocumentDetails(
                        "MODEL-SALE-1",
                        SalesDocumentKind.SALE,
                        "SALE",
                        "COMPLETED",
                        now,
                        businessDate,
                        now
                ),
                new SalesDocumentAmounts(
                        new BigDecimal("100.00"),
                        new BigDecimal("60.00")
                ),
                graph.rawRecord
        );
    }

    private void persistFactsAndOperations(
            ModelGraph graph,
            Instant now,
            LocalDate businessDate
    ) {
        SalesDocumentItem salesItem = new SalesDocumentItem(
                new SalesItemIdentity(
                        graph.salesDocument, "sale-item-model-test", null, graph.product
                ),
                new SalesItemClassification(
                        graph.product.getName(),
                        graph.sourceGroup.getName(),
                        graph.category,
                        graph.categoryAssignment,
                        "model-v1",
                        ProductConditionType.NEW
                ),
                new SalesItemAmounts(
                        BigDecimal.ONE,
                        new BigDecimal("100.00"),
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO,
                        new BigDecimal("100.00"),
                        new BigDecimal("60.00")
                ),
                CostQuality.KNOWN,
                false
        );
        SalesPayment payment = new SalesPayment(
                graph.salesDocument,
                "payment-model-test",
                PaymentMethod.CARD,
                new BigDecimal("100.00"),
                now
        );
        ReportSnapshot report = new ReportSnapshot(
                graph.store,
                new ReportDefinition(
                        "MODEL_REPORT",
                        ReportPeriodType.DAY,
                        businessDate,
                        businessDate,
                        ReportStatus.DRAFT,
                        "formula-v1",
                        "model-v1"
                ),
                new ReportContent(null, "{}", now, graph.user, null, null)
        );
        DataQualityIssue issue = DataQualityIssue.open(
                graph.store,
                "PRODUCT",
                "product-model-test",
                "MODEL_WARNING",
                DataQualitySeverity.WARNING,
                "Model persistence warning",
                now
        );
        AuditLog auditLog = new AuditLog(
                graph.user,
                graph.store,
                "MODEL_PERSISTED",
                "PRODUCT",
                "product-model-test",
                InetAddress.getLoopbackAddress(),
                "integration-test"
        );
        SyncRun failedRun = SyncRun.startStoreSync(graph.connection, now);
        failedRun.fail(0, "Expected model test failure", now.plusSeconds(1));
        SyncRunError syncError = SyncRunError.storeSyncFailure(
                failedRun, "Expected model test failure", now.plusSeconds(1)
        );

        entityManager.persist(salesItem);
        entityManager.persist(payment);
        entityManager.persist(report);
        entityManager.persist(issue);
        entityManager.persist(auditLog);
        entityManager.persist(failedRun);
        entityManager.persist(syncError);
    }

    private void updateMutableEntities(ModelGraph graph, Instant now) {
        graph.store.updateFromLiveSklad("Model Test Store Updated", "Updated test address");
        graph.user.deactivate();
        graph.employee.updateFromLiveSklad(
                "Model Employee Updated",
                false,
                now.plusSeconds(1)
        );
        graph.employeeStoreAssignment.update(false, false);
        graph.inventory.update(
                new InventoryValues(
                        new BigDecimal("2.00"),
                        new BigDecimal("110.00"),
                        new BigDecimal("65.00"),
                        now.plusSeconds(1)
                ),
                graph.syncRun
        );
    }

    private void assertDbManagedTimestampsAreSynchronized(ModelGraph graph) {
        assertDbTimestamp(
                "SELECT created_at FROM integration_connections WHERE id = ?",
                graph.connection.getCreatedAt(),
                graph.connection.getId()
        );
        assertDbTimestamp(
                "SELECT updated_at FROM integration_connections WHERE id = ?",
                graph.connection.getUpdatedAt(),
                graph.connection.getId()
        );
        assertDbTimestamp(
                "SELECT created_at FROM stores WHERE id = ?",
                graph.store.getCreatedAt(),
                graph.store.getId()
        );
        assertDbTimestamp(
                "SELECT updated_at FROM stores WHERE id = ?",
                graph.store.getUpdatedAt(),
                graph.store.getId()
        );
        assertDbTimestamp(
                "SELECT created_at FROM app_users WHERE id = ?",
                graph.user.getCreatedAt(),
                graph.user.getId()
        );
        assertDbTimestamp(
                "SELECT updated_at FROM app_users WHERE id = ?",
                graph.user.getUpdatedAt(),
                graph.user.getId()
        );
        assertDbTimestamp(
                "SELECT granted_at FROM user_store_access WHERE user_id = ? AND store_id = ?",
                graph.userStoreAccess.getGrantedAt(),
                graph.user.getId(),
                graph.store.getId()
        );
        assertDbTimestamp(
                "SELECT assigned_at FROM employee_store_assignments "
                        + "WHERE employee_id = ? AND store_id = ?",
                graph.employeeStoreAssignment.getAssignedAt(),
                graph.employee.getId(),
                graph.store.getId()
        );
        assertDbTimestamp(
                "SELECT updated_at FROM employee_store_assignments "
                        + "WHERE employee_id = ? AND store_id = ?",
                graph.employeeStoreAssignment.getUpdatedAt(),
                graph.employee.getId(),
                graph.store.getId()
        );
        assertThat(graph.employeeStoreAssignment.getVersion()).isPositive();
        assertThat(graph.employee.getFullName()).isEqualTo("Model Employee Updated");
        assertThat(graph.employee.isActive()).isFalse();
        assertDbTimestamp(
                "SELECT created_at FROM store_product_inventory "
                        + "WHERE store_id = ? AND product_id = ?",
                graph.inventory.getCreatedAt(),
                graph.store.getId(),
                graph.product.getId()
        );
        assertDbTimestamp(
                "SELECT updated_at FROM store_product_inventory "
                        + "WHERE store_id = ? AND product_id = ?",
                graph.inventory.getUpdatedAt(),
                graph.store.getId(),
                graph.product.getId()
        );
        assertThat(graph.category.getCreatedAt()).isNotNull();
        assertThat(graph.category.getUpdatedAt()).isNotNull();
    }

    private void assertDbTimestamp(String sql, Instant entityValue, Object... parameters) {
        Instant databaseValue = jdbcTemplate.queryForObject(sql, Instant.class, parameters);
        assertThat(entityValue).isNotNull().isEqualTo(databaseValue);
    }

    private void assertEveryEntityWasPersisted() {
        assertThat(entityManager.getMetamodel().getEntities()).hasSize(23);
        for (EntityType<?> entityType : entityManager.getMetamodel().getEntities()) {
            Long count = entityManager.createQuery(
                    "select count(entity) from " + entityType.getName() + " entity",
                    Long.class
            ).getSingleResult();
            assertThat(count)
                    .as("%s must be persistable through its application API", entityType.getName())
                    .isPositive();
        }
    }

    private static final class ModelGraph {

        private IntegrationConnection connection;
        private Store store;
        private AppUser user;
        private SyncJob syncJob;
        private SyncRun syncRun;
        private Employee employee;
        private SourceProductGroup sourceGroup;
        private Product product;
        private AnalyticsCategory category;
        private RawRecordVersion rawRecord;
        private ProductCategoryAssignment categoryAssignment;
        private SalesDocument salesDocument;
        private UserStoreAccess userStoreAccess;
        private EmployeeStoreAssignment employeeStoreAssignment;
        private StoreProductInventory inventory;
    }
}
