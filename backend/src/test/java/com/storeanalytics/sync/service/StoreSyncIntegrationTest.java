package com.storeanalytics.sync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.storeanalytics.auth.model.UserRole;
import com.storeanalytics.employee.model.Employee;
import com.storeanalytics.employee.repository.EmployeeRepository;
import com.storeanalytics.employee.repository.EmployeeStoreAssignmentRepository;
import com.storeanalytics.integration.livesklad.client.LiveSkladClient;
import com.storeanalytics.integration.livesklad.dto.LiveSkladCashItemPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladCashRegisterPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladCashTransactionPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladEmployeePayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladReturnDetailPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladSaleDetailPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladSaleSummaryPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladSalePositionPayload;
import com.storeanalytics.integration.livesklad.exception.LiveSkladException;
import com.storeanalytics.metrics.model.ReportPeriodType;
import com.storeanalytics.metrics.model.ReportStatus;
import com.storeanalytics.metrics.model.ReportType;
import com.storeanalytics.product.model.AnalyticsCategoryKind;
import com.storeanalytics.product.model.AttachDenominatorCode;
import com.storeanalytics.product.model.CategoryAssignmentSource;
import com.storeanalytics.product.model.DeviceFamily;
import com.storeanalytics.product.model.ProductConditionType;
import com.storeanalytics.product.model.ProductSourceKind;
import com.storeanalytics.quality.model.DataQualitySeverity;
import com.storeanalytics.quality.model.DataQualityStatus;
import com.storeanalytics.sales.model.CostQuality;
import com.storeanalytics.sales.model.PaymentMethod;
import com.storeanalytics.sales.model.SalesDocumentKind;
import com.storeanalytics.integration.livesklad.dto.LiveSkladStorePayload;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.repository.StoreRepository;
import com.storeanalytics.sync.exception.EmployeeSyncException;
import com.storeanalytics.sync.exception.SalesSyncCapacityException;
import com.storeanalytics.sync.exception.SalesSyncException;
import com.storeanalytics.sync.exception.StoreSyncException;
import com.storeanalytics.sync.model.NormalizationStatus;
import com.storeanalytics.sync.model.SourceSystem;
import com.storeanalytics.sync.model.SyncScope;
import com.storeanalytics.sync.model.SyncStatus;
import com.storeanalytics.sync.model.SyncTriggerType;
import com.storeanalytics.sync.repository.RawRecordVersionRepository;
import com.storeanalytics.sync.repository.SyncRunErrorRepository;
import com.storeanalytics.sync.repository.SyncRunRepository;
import jakarta.persistence.Column;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Table;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.persister.entity.AbstractEntityPersister;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Import(StoreSyncIntegrationTest.FakeClientConfiguration.class)
class StoreSyncIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private StoreSyncService storeSyncService;

    @Autowired
    private EmployeeSyncService employeeSyncService;
    @Autowired
    private SalesSyncService salesSyncService;
    @Autowired
    private ReturnSyncService returnSyncService;


    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private EmployeeStoreAssignmentRepository assignmentRepository;

    @Autowired
    private RawRecordVersionRepository rawRecordRepository;

    @Autowired
    private SyncRunRepository syncRunRepository;

    @Autowired
    private SyncRunErrorRepository syncRunErrorRepository;

    @Autowired
    private FakeLiveSkladClient fakeClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private ApplicationContext applicationContext;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void cleanDatabaseAndResetFixture() throws IOException {
        jdbcTemplate.update("DELETE FROM data_quality_issues");
        jdbcTemplate.update("DELETE FROM sales_payments");
        jdbcTemplate.update("DELETE FROM sales_document_items");
        jdbcTemplate.update("DELETE FROM sales_documents");
        jdbcTemplate.update("DELETE FROM product_category_assignments");
        jdbcTemplate.update("DELETE FROM store_product_inventory_history");
        jdbcTemplate.update("DELETE FROM store_product_inventory");
        jdbcTemplate.update("DELETE FROM raw_record_versions");
        jdbcTemplate.update("DELETE FROM sync_run_errors");
        jdbcTemplate.update("DELETE FROM sync_runs");
        jdbcTemplate.update("DELETE FROM products");
        jdbcTemplate.update("DELETE FROM cash_registers");
        jdbcTemplate.update("DELETE FROM employee_store_assignments");
        jdbcTemplate.update("DELETE FROM employees");
        jdbcTemplate.update("DELETE FROM stores");
        jdbcTemplate.update(
                "DELETE FROM integration_connections WHERE connection_key <> 'livesklad-default'"
        );
        fakeClient.setStores(readFixture());
        fakeClient.setEmployees(readEmployeeFixture());
        fakeClient.setSales(Map.of(), Map.of());
        fakeClient.setReturns(List.of(), Map.of(), Map.of(), Map.of());
    }

    @Test
    void flywayCreatesEntireCoreSchema() {
        Integer tableCount = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name <> 'flyway_schema_history'
                """,
                Integer.class
        );

        assertThat(tableCount).isEqualTo(43);
        assertThat(entityManagerFactory.getMetamodel().getEntities()).hasSize(39);
        assertThat(applicationContext.getBeanNamesForType(JpaRepository.class)).hasSize(39);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM integration_connections WHERE connection_key = 'livesklad-default'",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM pg_trigger
                WHERE tgname LIKE 'tr_%_updated_at'
                  AND NOT tgisinternal
                """,
                Integer.class
        )).isEqualTo(23);
    }

    @Test
    void hibernateMapsEveryApplicationTableColumn() {
        Map<String, Set<String>> databaseColumns = new TreeMap<>();
        jdbcTemplate.query(
                """
                SELECT table_name, column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name NOT IN (
                      'flyway_schema_history',
                      'auth_login_throttles',
                      'audit_retention_holds',
                      'store_product_inventory_daily',
                      'store_product_inventory_monthly'
                  )
                ORDER BY table_name, ordinal_position
                """,
                resultSet -> {
                    databaseColumns
                            .computeIfAbsent(resultSet.getString("table_name"), ignored -> new TreeSet<>())
                            .add(resultSet.getString("column_name"));
                }
        );

        SessionFactoryImplementor sessionFactory =
                entityManagerFactory.unwrap(SessionFactoryImplementor.class);
        Map<String, Set<String>> hibernateColumns = new TreeMap<>();
        entityManagerFactory.getMetamodel().getEntities().forEach(entityType -> {
            AbstractEntityPersister persister = (AbstractEntityPersister) sessionFactory
                    .getMappingMetamodel()
                    .getEntityDescriptor(entityType.getJavaType());
            Set<String> columns = new TreeSet<>();
            addColumns(columns, persister.getIdentifierColumnNames());
            for (String propertyName : persister.getPropertyNames()) {
                addColumns(columns, persister.getPropertyColumnNames(propertyName));
            }
            hibernateColumns.put(unqualifiedName(persister.getRootTableName()), columns);
        });

        assertThat(hibernateColumns).isEqualTo(databaseColumns);
    }

    @Test
    void numericPrecisionAndScaleMatchJpaColumns() {
        entityManagerFactory.getMetamodel().getEntities().forEach(entityType -> {
            Table table = entityType.getJavaType().getAnnotation(Table.class);
            for (java.lang.reflect.Field field : entityType.getJavaType().getDeclaredFields()) {
                Column column = field.getAnnotation(Column.class);
                if (column == null || column.precision() == 0) {
                    continue;
                }
                Map<String, Object> databaseType = jdbcTemplate.queryForMap(
                        """
                        SELECT numeric_precision, numeric_scale
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = ?
                          AND column_name = ?
                        """,
                        table.name(),
                        physicalColumnName(field, column)
                );
                assertThat(((Number) databaseType.get("numeric_precision")).intValue())
                        .as("%s.%s precision", table.name(), field.getName())
                        .isEqualTo(column.precision());
                assertThat(((Number) databaseType.get("numeric_scale")).intValue())
                        .as("%s.%s scale", table.name(), field.getName())
                        .isEqualTo(column.scale());
            }
        });
    }

    @Test
    void javaEnumsMatchDatabaseCheckConstraints() {
        expectedEnumValues().forEach((column, expected) ->
                assertThat(databaseCheckValues(column))
                        .as("%s.%s", column.table(), column.column())
                        .isEqualTo(expected)
        );
    }

    @Test
    void scopesExternalIdentityByConnectionAndUpdatesTimestampInDatabase() {
        storeSyncService.synchronize();

        UUID connectionId = jdbcTemplate.queryForObject(
                "SELECT id FROM integration_connections WHERE connection_key = 'livesklad-default'",
                UUID.class
        );
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM stores WHERE connection_id = ?",
                Integer.class,
                connectionId
        )).isEqualTo(2);

        Instant previousUpdatedAt = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM stores WHERE external_id = 'store-fixture-1'",
                Instant.class
        );
        jdbcTemplate.update(
                """
                UPDATE stores
                SET address = 'Changed by schema invariant',
                    updated_at = TIMESTAMPTZ '2000-01-01 00:00:00Z'
                WHERE external_id = 'store-fixture-1'
                """
        );
        Instant triggeredUpdatedAt = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM stores WHERE external_id = 'store-fixture-1'",
                Instant.class
        );
        assertThat(triggeredUpdatedAt).isAfterOrEqualTo(previousUpdatedAt);
        assertThat(triggeredUpdatedAt).isNotEqualTo(Instant.parse("2000-01-01T00:00:00Z"));

        UUID secondConnectionId = jdbcTemplate.queryForObject(
                """
                INSERT INTO integration_connections (
                    connection_key, source_system, display_name
                ) VALUES ('livesklad-second', 'LIVESKLAD', 'Second fixture connection')
                RETURNING id
                """,
                UUID.class
        );
        jdbcTemplate.update(
                """
                INSERT INTO stores (connection_id, source_system, external_id, name)
                VALUES (?, 'LIVESKLAD', 'store-fixture-1', 'Same external ID, another connection')
                """,
                secondConnectionId
        );
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM stores WHERE external_id = 'store-fixture-1'",
                Integer.class
        )).isEqualTo(2);

        jdbcTemplate.update(
                """
                INSERT INTO cash_registers (
                    connection_id, store_id, source_system, external_id, name
                )
                SELECT connection_id, id, 'LIVESKLAD', 'cash-register-shared', 'Default cash register'
                FROM stores
                WHERE connection_id = ? AND external_id = 'store-fixture-1'
                """,
                connectionId
        );
        jdbcTemplate.update(
                """
                INSERT INTO cash_registers (
                    connection_id, store_id, source_system, external_id, name
                )
                SELECT connection_id, id, 'LIVESKLAD', 'cash-register-shared', 'Second cash register'
                FROM stores
                WHERE connection_id = ? AND external_id = 'store-fixture-1'
                """,
                secondConnectionId
        );
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM cash_registers WHERE external_id = 'cash-register-shared'",
                Integer.class
        )).isEqualTo(2);

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO cash_registers (
                    connection_id, store_id, source_system, external_id, name
                )
                SELECT connection_id, id, 'LIVESKLAD', 'cash-register-shared', 'Duplicate cash register'
                FROM stores
                WHERE connection_id = ? AND external_id = 'store-fixture-1'
                """,
                secondConnectionId
        )).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsCrossConnectionReferences() {
        storeSyncService.synchronize();

        UUID defaultStoreId = jdbcTemplate.queryForObject(
                "SELECT id FROM stores WHERE external_id = 'store-fixture-1'",
                UUID.class
        );
        UUID secondLiveSkladConnection = jdbcTemplate.queryForObject(
                """
                INSERT INTO integration_connections (
                    connection_key, source_system, display_name
                ) VALUES ('constraint-live', 'LIVESKLAD', 'Constraint LiveSklad')
                RETURNING id
                """,
                UUID.class
        );
        UUID amoCrmConnection = jdbcTemplate.queryForObject(
                """
                INSERT INTO integration_connections (
                    connection_key, source_system, display_name
                ) VALUES ('constraint-amo', 'AMOCRM', 'Constraint amoCRM')
                RETURNING id
                """,
                UUID.class
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO stores (
                    connection_id, source_system, external_id, name
                ) VALUES (?, 'LIVESKLAD', 'wrong-provider', 'Wrong provider')
                """,
                amoCrmConnection
        )).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO cash_registers (
                    connection_id, store_id, source_system, external_id, name
                ) VALUES (?, ?, 'LIVESKLAD', 'wrong-store', 'Wrong store')
                """,
                secondLiveSkladConnection,
                defaultStoreId
        )).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void databaseProtectsEmployeeIdentityAndStoreAssignments() {
        storeSyncService.synchronize();

        UUID connectionId = jdbcTemplate.queryForObject(
                """
                SELECT id
                FROM integration_connections
                WHERE connection_key = 'livesklad-default'
                """,
                UUID.class
        );
        List<UUID> storeIds = jdbcTemplate.queryForList(
                "SELECT id FROM stores ORDER BY external_id",
                UUID.class
        );
        UUID employeeId = jdbcTemplate.queryForObject(
                """
                INSERT INTO employees (
                    connection_id, source_system, external_id, full_name
                ) VALUES (?, 'LIVESKLAD', 'shared-employee', 'Shared employee')
                RETURNING id
                """,
                UUID.class,
                connectionId
        );

        for (UUID storeId : storeIds) {
            jdbcTemplate.update(
                    """
                    INSERT INTO employee_store_assignments (employee_id, store_id)
                    VALUES (?, ?)
                    """,
                    employeeId,
                    storeId
            );
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM employee_store_assignments WHERE employee_id = ?",
                Integer.class,
                employeeId
        )).isEqualTo(2);

        UUID manualEmployeeId = jdbcTemplate.queryForObject(
                """
                INSERT INTO employees (source_system, external_id, full_name)
                VALUES ('MANUAL', 'manual-employee', 'Manual employee')
                RETURNING id
                """,
                UUID.class
        );
        jdbcTemplate.update(
                """
                INSERT INTO employee_store_assignments (employee_id, store_id)
                VALUES (?, ?)
                """,
                manualEmployeeId,
                storeIds.getFirst()
        );

        UUID otherConnectionId = jdbcTemplate.queryForObject(
                """
                INSERT INTO integration_connections (
                    connection_key, source_system, display_name
                ) VALUES ('employee-other', 'LIVESKLAD', 'Other employee source')
                RETURNING id
                """,
                UUID.class
        );
        UUID otherEmployeeId = jdbcTemplate.queryForObject(
                """
                INSERT INTO employees (
                    connection_id, source_system, external_id, full_name
                ) VALUES (?, 'LIVESKLAD', 'shared-employee', 'Other shared employee')
                RETURNING id
                """,
                UUID.class,
                otherConnectionId
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO employees (
                    connection_id, source_system, external_id, full_name
                ) VALUES (?, 'LIVESKLAD', 'shared-employee', 'Duplicate employee')
                """,
                connectionId
        )).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO employee_store_assignments (employee_id, store_id)
                VALUES (?, ?)
                """,
                otherEmployeeId,
                storeIds.getFirst()
        )).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE employees SET external_id = 'changed-identity' WHERE id = ?",
                employeeId
        )).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO employees (source_system, external_id, full_name)
                VALUES ('MANUAL', NULL, 'Missing identity')
                """
        )).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void databaseProtectsInventoryConnectionAndHistoryImmutability() {
        storeSyncService.synchronize();

        UUID connectionId = jdbcTemplate.queryForObject(
                """
                SELECT id
                FROM integration_connections
                WHERE connection_key = 'livesklad-default'
                """,
                UUID.class
        );
        UUID storeId = jdbcTemplate.queryForObject(
                "SELECT id FROM stores WHERE external_id = 'store-fixture-1'",
                UUID.class
        );
        UUID syncRunId = jdbcTemplate.queryForObject(
                "SELECT id FROM sync_runs ORDER BY started_at DESC LIMIT 1",
                UUID.class
        );
        UUID productId = jdbcTemplate.queryForObject(
                """
                INSERT INTO products (
                    connection_id, source_system, external_id, name
                ) VALUES (?, 'LIVESKLAD', 'inventory-product', 'Inventory product')
                RETURNING id
                """,
                UUID.class,
                connectionId
        );
        UUID historyId = jdbcTemplate.queryForObject(
                """
                INSERT INTO store_product_inventory_history (
                    store_id, product_id, quantity, observed_at, sync_run_id
                ) VALUES (?, ?, 1.000, clock_timestamp(), ?)
                RETURNING id
                """,
                UUID.class,
                storeId,
                productId,
                syncRunId
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE store_product_inventory_history SET quantity = 2.000 WHERE id = ?",
                historyId
        )).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        UUID otherConnectionId = jdbcTemplate.queryForObject(
                """
                INSERT INTO integration_connections (
                    connection_key, source_system, display_name
                ) VALUES ('inventory-other', 'LIVESKLAD', 'Other inventory source')
                RETURNING id
                """,
                UUID.class
        );
        UUID otherProductId = jdbcTemplate.queryForObject(
                """
                INSERT INTO products (
                    connection_id, source_system, external_id, name
                ) VALUES (?, 'LIVESKLAD', 'other-product', 'Other product')
                RETURNING id
                """,
                UUID.class,
                otherConnectionId
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO store_product_inventory (store_id, product_id, quantity)
                VALUES (?, ?, 1.000)
                """,
                storeId,
                otherProductId
        )).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO store_product_inventory_history (
                    store_id, product_id, quantity, observed_at, sync_run_id
                ) VALUES (?, ?, 1.000, clock_timestamp(), ?)
                """,
                storeId,
                otherProductId,
                syncRunId
        )).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void synchronizesEmployeesAcrossStoresIdempotently() {
        storeSyncService.synchronize();

        EmployeeSyncResult first = employeeSyncService.synchronize();

        assertThat(first.status()).isEqualTo(SyncStatus.SUCCESS);
        assertThat(first.recordsFetched()).isEqualTo(3);
        assertThat(first.recordsCreated()).isEqualTo(3);
        assertThat(first.recordsUpdated()).isZero();
        assertThat(first.recordsSkipped()).isZero();
        assertThat(first.assignmentsDeactivated()).isZero();
        assertThat(first.employeesDeactivated()).isZero();
        assertThat(employeeRepository.count()).isEqualTo(2);
        assertThat(assignmentRepository.count()).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM raw_record_versions WHERE entity_type = 'EMPLOYEE'",
                Integer.class
        )).isEqualTo(3);

        Employee sharedEmployee = employeeRepository.findAll().stream()
                .filter(employee -> employee.getExternalId().equals("employee-shared"))
                .findFirst()
                .orElseThrow();
        assertThat(assignmentRepository.findAllByEmployeeId(sharedEmployee.getId()))
                .hasSize(2)
                .allSatisfy(assignment -> {
                    assertThat(assignment.isActive()).isTrue();
                    assertThat(assignment.participatesInRanking()).isFalse();
                });

        EmployeeSyncResult unchanged = employeeSyncService.synchronize();

        assertThat(unchanged.recordsCreated()).isZero();
        assertThat(unchanged.recordsUpdated()).isZero();
        assertThat(unchanged.recordsSkipped()).isEqualTo(3);
        assertThat(employeeRepository.count()).isEqualTo(2);
        assertThat(assignmentRepository.count()).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM raw_record_versions WHERE entity_type = 'EMPLOYEE'",
                Integer.class
        )).isEqualTo(3);
    }

    @Test
    void updatesDeactivatesAndReactivatesEmployeesWithoutDuplicatingRawVersions()
            throws IOException {
        storeSyncService.synchronize();
        employeeSyncService.synchronize();

        fakeClient.setEmployees(Map.of(
                "store-fixture-1", List.of(employeePayload(
                        "employee-shared", "Shared Employee Updated"
                )),
                "store-fixture-2", List.of(employeePayload(
                        "employee-shared", "Shared Employee Updated"
                ))
        ));

        EmployeeSyncResult changed = employeeSyncService.synchronize();

        assertThat(changed.recordsFetched()).isEqualTo(2);
        assertThat(changed.recordsUpdated()).isEqualTo(1);
        assertThat(changed.recordsSkipped()).isEqualTo(1);
        assertThat(changed.assignmentsDeactivated()).isEqualTo(1);
        assertThat(changed.employeesDeactivated()).isEqualTo(1);
        Employee northEmployee = employeeRepository.findAll().stream()
                .filter(employee -> employee.getExternalId().equals("employee-north"))
                .findFirst()
                .orElseThrow();
        assertThat(northEmployee.isActive()).isFalse();
        assertThat(assignmentRepository.findAllByEmployeeId(northEmployee.getId()))
                .singleElement()
                .satisfies(assignment -> assertThat(assignment.isActive()).isFalse());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM raw_record_versions WHERE entity_type = 'EMPLOYEE'",
                Integer.class
        )).isEqualTo(5);

        fakeClient.setEmployees(readEmployeeFixture());
        EmployeeSyncResult reactivated = employeeSyncService.synchronize();

        assertThat(reactivated.recordsUpdated()).isEqualTo(2);
        assertThat(reactivated.recordsSkipped()).isEqualTo(1);
        assertThat(reactivated.assignmentsDeactivated()).isZero();
        assertThat(reactivated.employeesDeactivated()).isZero();
        assertThat(employeeRepository.findById(northEmployee.getId()))
                .get()
                .extracting(Employee::isActive)
                .isEqualTo(true);
        assertThat(assignmentRepository.findAllByEmployeeId(northEmployee.getId()))
                .singleElement()
                .satisfies(assignment -> assertThat(assignment.isActive()).isTrue());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM raw_record_versions WHERE entity_type = 'EMPLOYEE'",
                Integer.class
        )).isEqualTo(5);
    }

    @Test
    void employeeSourceFailureCreatesSanitizedFailedRunWithoutPartialWrites() {
        storeSyncService.synchronize();
        fakeClient.failEmployeesForStore(
                "store-fixture-2",
                new IllegalStateException("fixture employee source failure")
        );

        assertThatThrownBy(employeeSyncService::synchronize)
                .isInstanceOf(EmployeeSyncException.class);

        assertThat(employeeRepository.count()).isZero();
        assertThat(assignmentRepository.count()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM raw_record_versions WHERE entity_type = 'EMPLOYEE'",
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT status
                FROM sync_runs
                WHERE sync_scope = 'EMPLOYEES'
                """,
                String.class
        )).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT records_fetched
                FROM sync_runs
                WHERE sync_scope = 'EMPLOYEES'
                """,
                Integer.class
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT error_message FROM sync_run_errors",
                String.class
        )).isEqualTo("Employee synchronization failed: IllegalStateException");
    }

    @Test
    void persistsOnlyTheApprovedRawStoreFields() throws IOException {
        List<LiveSkladStorePayload> stores = new ArrayList<>(readFixture());
        LiveSkladStorePayload original = stores.getFirst();
        ObjectNode vendorPayload = (ObjectNode) original.rawPayload().deepCopy();
        vendorPayload.put("ownerEmail", "private@example.com");
        vendorPayload.put("accessToken", "must-not-be-retained");
        stores.set(0, new LiveSkladStorePayload(
                original.externalId(),
                original.name(),
                original.address(),
                original.color(),
                vendorPayload
        ));
        fakeClient.setStores(stores);

        storeSyncService.synchronize();

        String retainedPayload = jdbcTemplate.queryForObject(
                """
                SELECT payload::text
                FROM raw_record_versions
                WHERE entity_type = 'STORE' AND external_id = ?
                """,
                String.class,
                original.externalId()
        );
        assertThat(retainedPayload)
                .contains(original.externalId(), original.name(), original.address())
                .doesNotContain(
                        "ownerEmail",
                        "private@example.com",
                        "accessToken",
                        "must-not-be-retained"
                );
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT payload_policy_version
                FROM raw_record_versions
                WHERE entity_type = 'STORE' AND external_id = ?
                """,
                Integer.class,
                original.externalId()
        )).isEqualTo(1);
    }

    @Test
    void synchronizesStoresIdempotentlyAndCreatesNewRawVersionOnlyForChangedPayload()
            throws IOException {
        StoreSyncResult first = storeSyncService.synchronize();

        assertThat(first.status()).isEqualTo(SyncStatus.SUCCESS);
        assertThat(first.recordsFetched()).isEqualTo(2);
        assertThat(first.recordsCreated()).isEqualTo(2);
        assertThat(first.recordsUpdated()).isZero();
        assertThat(first.recordsSkipped()).isZero();
        assertThat(storeRepository.count()).isEqualTo(2);
        assertThat(rawRecordRepository.count()).isEqualTo(2);

        StoreSyncResult unchanged = storeSyncService.synchronize();

        assertThat(unchanged.recordsCreated()).isZero();
        assertThat(unchanged.recordsUpdated()).isZero();
        assertThat(unchanged.recordsSkipped()).isEqualTo(2);
        assertThat(rawRecordRepository.count()).isEqualTo(2);

        List<LiveSkladStorePayload> changedStores = new ArrayList<>(readFixture());
        LiveSkladStorePayload original = changedStores.getFirst();
        ObjectNode changedRaw = (ObjectNode) original.rawPayload().deepCopy();
        changedRaw.put("name", "Fixture North Updated");
        changedStores.set(0, new LiveSkladStorePayload(
                original.externalId(),
                "Fixture North Updated",
                original.address(),
                original.color(),
                changedRaw
        ));
        fakeClient.setStores(changedStores);

        StoreSyncResult changed = storeSyncService.synchronize();

        assertThat(changed.recordsCreated()).isZero();
        assertThat(changed.recordsUpdated()).isEqualTo(1);
        assertThat(changed.recordsSkipped()).isEqualTo(1);
        assertThat(rawRecordRepository.count()).isEqualTo(3);
        assertThat(syncRunRepository.count()).isEqualTo(3);
        assertThat(storeRepository.findAll())
                .extracting(Store::getName)
                .containsExactlyInAnyOrder("Fixture North Updated", "Fixture South");
    }

    @Test
    void recordsFailedRunAndSanitizedErrorWhenSourceRequestFails() {
        fakeClient.failWith(new IllegalStateException("fixture source failure"));

        assertThatThrownBy(storeSyncService::synchronize)
                .isInstanceOf(StoreSyncException.class);

        assertThat(syncRunRepository.findAll()).singleElement().satisfies(run -> {
            assertThat(run.getStatus()).isEqualTo(SyncStatus.FAILED);
            assertThat(run.getRecordsFailed()).isEqualTo(1);
        });
        assertThat(syncRunErrorRepository.count()).isEqualTo(1);
    }

    @Test
    void synchronizesSalesIdempotentlyWithBusinessDateAndUnmappedSnapshot() {
        storeSyncService.synchronize();
        employeeSyncService.synchronize();
        Instant occurredAt = Instant.parse("2026-07-01T22:30:00Z");
        SalesSyncPeriod period = new SalesSyncPeriod(
                Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-07-03T00:00:00Z")
        );
        LiveSkladSaleSummaryPayload summary = saleSummary(
                "sale-1", "S-1", occurredAt, "120.00", "100.00", "60.00"
        );
        LiveSkladSalePositionPayload position = salePosition(
                "position-1", "product-1", "Fixture Product",
                "1.000", "120.00", "100.00", "60.00"
        );
        LiveSkladSaleDetailPayload detail = saleDetail(
                "sale-1", "S-1", occurredAt,
                Instant.parse("2026-07-01T22:35:00Z"),
                new SaleParties("store-fixture-1", "employee-north"),
                new PaymentAmounts("40.00", "60.00", "0.00"), List.of(position)
        );
        fakeClient.setSales(
                Map.of(
                        "store-fixture-1", List.of(summary),
                        "store-fixture-2", List.of()
                ),
                Map.of("sale-1", detail)
        );

        SalesSyncResult first = salesSyncService.synchronize(period);
        SalesSyncResult unchanged = salesSyncService.synchronize(period);

        assertThat(first.status()).isEqualTo(SyncStatus.SUCCESS);
        assertThat(first.recordsFetched()).isEqualTo(1);
        assertThat(first.recordsCreated()).isEqualTo(1);
        assertThat(first.productsCreated()).isEqualTo(1);
        assertThat(first.itemsCreated()).isEqualTo(1);
        assertThat(first.paymentsCreated()).isEqualTo(2);
        assertThat(first.qualityIssuesOpened()).isEqualTo(1);
        assertThat(unchanged.recordsSkipped()).isEqualTo(1);
        assertThat(unchanged.productsCreated()).isZero();
        assertThat(unchanged.itemsCreated()).isZero();
        assertThat(unchanged.paymentsCreated()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sales_documents",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT business_date FROM sales_documents WHERE external_id = 'sale-1'",
                LocalDate.class
        )).isEqualTo(LocalDate.parse("2026-07-02"));
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT category.code
                FROM sales_document_items item
                JOIN analytics_categories category
                  ON category.id = item.analytics_category_id
                WHERE item.external_id = 'position-1'
                """,
                String.class
        )).isEqualTo("UNMAPPED");
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM raw_record_versions
                WHERE entity_type = 'SALE_DOCUMENT'
                """,
                Integer.class
        )).isEqualTo(1);
    }

    @Test
    void appliesSalesCorrectionsSoftDeletesMissingFactsAndResolvesQualityIssue() {
        storeSyncService.synchronize();
        employeeSyncService.synchronize();
        Instant occurredAt = Instant.parse("2026-07-01T10:00:00Z");
        SalesSyncPeriod period = new SalesSyncPeriod(
                Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-07-02T00:00:00Z")
        );
        LiveSkladSalePositionPayload firstPosition = salePosition(
                "position-1", "product-1", "First Product",
                "1.000", "120.00", "100.00", "60.00"
        );
        LiveSkladSalePositionPayload removedPosition = salePosition(
                "position-2", "product-2", "Removed Product",
                "1.000", "50.00", "50.00", "20.00"
        );
        LiveSkladSaleSummaryPayload initialSummary = saleSummary(
                "sale-corrected", "S-2", occurredAt, "170.00", "150.00", "80.00"
        );
        LiveSkladSaleDetailPayload initialDetail = saleDetail(
                "sale-corrected", "S-2", occurredAt,
                Instant.parse("2026-07-01T10:05:00Z"),
                new SaleParties("store-fixture-1", "employee-north"),
                new PaymentAmounts("140.00", "0.00", "0.00"),
                List.of(firstPosition, removedPosition)
        );
        fakeClient.setSales(
                Map.of("store-fixture-1", List.of(initialSummary)),
                Map.of("sale-corrected", initialDetail)
        );

        SalesSyncResult initial = salesSyncService.synchronize(period);

        assertThat(initial.qualityIssuesOpened()).isEqualTo(3);
        LiveSkladSaleSummaryPayload correctedSummary = saleSummary(
                "sale-corrected", "S-2", occurredAt, "120.00", "100.00", "60.00"
        );
        LiveSkladSaleDetailPayload correctedDetail = saleDetail(
                "sale-corrected", "S-2", occurredAt,
                Instant.parse("2026-07-01T10:10:00Z"),
                new SaleParties("store-fixture-1", "employee-north"),
                new PaymentAmounts("0.00", "100.00", "0.00"), List.of(firstPosition)
        );
        fakeClient.setSales(
                Map.of("store-fixture-1", List.of(correctedSummary)),
                Map.of("sale-corrected", correctedDetail)
        );

        SalesSyncResult corrected = salesSyncService.synchronize(period);

        assertThat(corrected.recordsUpdated()).isEqualTo(1);
        assertThat(corrected.itemsDeleted()).isEqualTo(1);
        assertThat(corrected.paymentsCreated()).isEqualTo(1);
        assertThat(corrected.paymentsDeleted()).isEqualTo(1);
        assertThat(corrected.qualityIssuesResolved()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT is_deleted
                FROM sales_document_items
                WHERE external_id = 'position-2'
                """,
                Boolean.class
        )).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT is_deleted
                FROM sales_payments
                WHERE external_id = 'sale-corrected:cash'
                """,
                Boolean.class
        )).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT status
                FROM data_quality_issues
                WHERE issue_code = 'SALE_PAYMENT_MISMATCH'
                """,
                String.class
        )).isEqualTo("RESOLVED");

        fakeClient.setSales(Map.of(), Map.of());
        SalesSyncResult deleted = salesSyncService.synchronize(period);

        assertThat(deleted.documentsDeleted()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT is_deleted
                FROM sales_documents
                WHERE external_id = 'sale-corrected'
                """,
                Boolean.class
        )).isTrue();

        fakeClient.setSales(
                Map.of("store-fixture-1", List.of(correctedSummary)),
                Map.of("sale-corrected", correctedDetail)
        );
        SalesSyncResult reactivated = salesSyncService.synchronize(period);

        assertThat(reactivated.recordsUpdated()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT is_deleted
                FROM sales_documents
                WHERE external_id = 'sale-corrected'
                """,
                Boolean.class
        )).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM raw_record_versions
                WHERE entity_type = 'SALE_DOCUMENT'
                """,
                Integer.class
        )).isEqualTo(2);
    }

    @Test
    void saleDetailFailureRollsBackFactsAndStoresOnlySanitizedRetryableError() {
        storeSyncService.synchronize();
        employeeSyncService.synchronize();
        Instant occurredAt = Instant.parse("2026-07-01T10:00:00Z");
        SalesSyncPeriod period = new SalesSyncPeriod(
                Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-07-02T00:00:00Z")
        );
        LiveSkladSaleSummaryPayload summary = saleSummary(
                "sale-failure", "S-F", occurredAt, "100.00", "100.00", "50.00"
        );
        LiveSkladSaleDetailPayload detail = saleDetail(
                "sale-failure", "S-F", occurredAt,
                Instant.parse("2026-07-01T10:05:00Z"),
                new SaleParties("store-fixture-1", "employee-north"),
                new PaymentAmounts("100.00", "0.00", "0.00"),
                List.of(salePosition(
                        "position-failure", "product-failure", "Failure Product",
                        "1.000", "100.00", "100.00", "50.00"
                ))
        );
        fakeClient.setSales(
                Map.of("store-fixture-1", List.of(summary)),
                Map.of("sale-failure", detail)
        );
        fakeClient.failSaleDetail(
                "sale-failure",
                new LiveSkladException("sensitive upstream response")
        );

        assertThatThrownBy(() -> salesSyncService.synchronize(period))
                .isInstanceOf(SalesSyncException.class)
                .hasMessage("Sales synchronization failed");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sales_documents",
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM products",
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM raw_record_versions
                WHERE entity_type = 'SALE_DOCUMENT'
                """,
                Integer.class
        )).isZero();
        Map<String, Object> failure = jdbcTemplate.queryForMap(
                """
                SELECT run.status, run.error_summary,
                       error.error_message, error.is_retryable
                FROM sync_runs run
                JOIN sync_run_errors error ON error.sync_run_id = run.id
                WHERE run.sync_scope = 'SALES'
                ORDER BY run.started_at DESC
                LIMIT 1
                """
        );
        assertThat(failure.get("status")).isEqualTo("FAILED");
        assertThat(failure.get("error_summary").toString())
                .doesNotContain("sensitive upstream response");
        assertThat(failure.get("error_message").toString())
                .doesNotContain("sensitive upstream response");
        assertThat(failure.get("is_retryable")).isEqualTo(true);
    }

    @Test
    void rejectsOversizedSalesWindowBeforeFetchingDetailsOrWritingFacts() {
        storeSyncService.synchronize();
        SalesSyncPeriod period = new SalesSyncPeriod(
                Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-07-02T00:00:00Z")
        );
        List<LiveSkladSaleSummaryPayload> summaries = new ArrayList<>();
        for (int index = 1; index <= 71; index++) {
            summaries.add(saleSummary(
                    "sale-capacity-" + index,
                    "S-C-" + index,
                    Instant.parse("2026-07-01T10:00:00Z"),
                    "1.00",
                    "1.00",
                    "0.50"
            ));
        }
        fakeClient.setSales(
                Map.of("store-fixture-1", List.copyOf(summaries)),
                Map.of()
        );

        assertThatThrownBy(() -> salesSyncService.synchronize(period))
                .isInstanceOfSatisfying(
                        SalesSyncCapacityException.class,
                        exception -> {
                            assertThat(exception.getRecordCount()).isEqualTo(71);
                            assertThat(exception.getMaximumRecordCount()).isEqualTo(70);
                        }
                );

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sales_documents",
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT records_fetched
                FROM sync_runs
                WHERE sync_scope = 'SALES'
                ORDER BY started_at DESC
                LIMIT 1
                """,
                Integer.class
        )).isEqualTo(71);
    }

    @Test
    void historicalBackfillDoesNotOverwriteNewerProductAttributes() {
        storeSyncService.synchronize();
        employeeSyncService.synchronize();
        LiveSkladSalePositionPayload currentPosition = salePosition(
                "position-current", "product-shared", "Current Product Name",
                "1.000", "100.00", "100.00", "50.00"
        );
        Instant currentOccurredAt = Instant.parse("2026-07-05T10:00:00Z");
        LiveSkladSaleSummaryPayload currentSummary = saleSummary(
                "sale-current", "S-N", currentOccurredAt,
                "100.00", "100.00", "50.00"
        );
        LiveSkladSaleDetailPayload currentDetail = saleDetail(
                "sale-current", "S-N", currentOccurredAt,
                Instant.parse("2026-07-05T10:05:00Z"),
                new SaleParties("store-fixture-1", "employee-north"),
                new PaymentAmounts("100.00", "0.00", "0.00"), List.of(currentPosition)
        );
        fakeClient.setSales(
                Map.of("store-fixture-1", List.of(currentSummary)),
                Map.of("sale-current", currentDetail)
        );
        salesSyncService.synchronize(new SalesSyncPeriod(
                Instant.parse("2026-07-05T00:00:00Z"),
                Instant.parse("2026-07-06T00:00:00Z")
        ));

        LiveSkladSalePositionPayload historicalPosition = salePosition(
                "position-historical", "product-shared", "Historical Product Name",
                "1.000", "80.00", "80.00", "40.00"
        );
        Instant historicalOccurredAt = Instant.parse("2026-07-01T10:00:00Z");
        LiveSkladSaleSummaryPayload historicalSummary = saleSummary(
                "sale-historical", "S-H", historicalOccurredAt,
                "80.00", "80.00", "40.00"
        );
        LiveSkladSaleDetailPayload historicalDetail = saleDetail(
                "sale-historical", "S-H", historicalOccurredAt,
                Instant.parse("2026-07-01T10:05:00Z"),
                new SaleParties("store-fixture-1", "employee-north"),
                new PaymentAmounts("80.00", "0.00", "0.00"), List.of(historicalPosition)
        );
        fakeClient.setSales(
                Map.of("store-fixture-1", List.of(historicalSummary)),
                Map.of("sale-historical", historicalDetail)
        );

        SalesSyncResult backfill = salesSyncService.synchronize(new SalesSyncPeriod(
                Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-07-02T00:00:00Z")
        ));

        assertThat(backfill.productsCreated()).isZero();
        assertThat(backfill.productsUpdated()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT name
                FROM products
                WHERE external_id = 'product-shared'
                """,
                String.class
        )).isEqualTo("Current Product Name");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sales_documents",
                Integer.class
        )).isEqualTo(2);
    }
    private LiveSkladSaleSummaryPayload saleSummary(
            String externalId,
            String number,
            Instant occurredAt,
            String gross,
            String net,
            String cost
    ) {
        ObjectNode raw = objectMapper.createObjectNode();
        raw.put("id", externalId);
        raw.put("number", number);
        raw.put("date", occurredAt.toString());
        raw.put("type", "sale");
        ObjectNode amounts = raw.putObject("summ");
        amounts.put("price", money(gross));
        amounts.put("soldPrice", money(net));
        if (cost == null) {
            amounts.putNull("purchasePrice");
        } else {
            amounts.put("purchasePrice", money(cost));
        }
        return new LiveSkladSaleSummaryPayload(
                externalId,
                number,
                occurredAt,
                "sale",
                money(gross),
                money(net),
                cost == null ? null : money(cost),
                raw
        );
    }

    private LiveSkladSaleDetailPayload saleDetail(
            String externalId,
            String number,
            Instant occurredAt,
            Instant sourceUpdatedAt,
            SaleParties parties,
            PaymentAmounts payments,
            List<LiveSkladSalePositionPayload> positions
    ) {
        ObjectNode raw = objectMapper.createObjectNode();
        raw.put("id", externalId);
        raw.put("number", number);
        raw.put("date", occurredAt.toString());
        raw.put("dateChange", sourceUpdatedAt.toString());
        raw.put("type", "sale");
        raw.putObject("shop").put("id", parties.storeExternalId());
        raw.putObject("customer").put("id", parties.employeeExternalId());
        ObjectNode cashNode = raw.putObject("cash");
        cashNode.put("money", money(payments.cash()));
        cashNode.put("bank", money(payments.card()));
        cashNode.put("invoice", money(payments.bankTransfer()));
        var rawPositions = raw.putArray("positions");
        for (LiveSkladSalePositionPayload position : positions) {
            ObjectNode rawPosition = rawPositions.addObject();
            rawPosition.put("positionId", position.externalId());
            rawPosition.put("nomenclatureId", position.productExternalId());
            rawPosition.put("code", position.code());
            rawPosition.put("article", position.sku());
            rawPosition.put("name", position.name());
            rawPosition.put("isWork", position.work());
            rawPosition.put("count", position.quantity());
            rawPosition.put("price", position.unitListPrice());
            rawPosition.put("soldPrice", position.unitSoldPrice());
            if (position.costAmount() == null) {
                rawPosition.putNull("purchasePriceSumm");
            } else {
                rawPosition.put("purchasePriceSumm", position.costAmount());
            }
        }
        return new LiveSkladSaleDetailPayload(
                externalId,
                number,
                occurredAt,
                sourceUpdatedAt,
                "sale",
                parties.storeExternalId(),
                parties.employeeExternalId(),
                "Fixture Employee",
                money(payments.cash()),
                money(payments.card()),
                money(payments.bankTransfer()),
                List.copyOf(positions),
                raw
        );
    }

    private LiveSkladSalePositionPayload salePosition(
            String externalId,
            String productExternalId,
            String name,
            String quantity,
            String listPrice,
            String soldPrice,
            String cost
    ) {
        return new LiveSkladSalePositionPayload(
                externalId, productExternalId, "CODE-" + productExternalId,
                "SKU-" + productExternalId, name, false, money(quantity),
                money(listPrice), money(soldPrice),
                cost == null ? null : money(cost)
        );
    }

    private BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    private String physicalColumnName(java.lang.reflect.Field field, Column column) {
        if (!column.name().isBlank()) {
            return column.name();
        }
        return field.getName()
                .replaceAll("([a-z])([A-Z])", "$1_$2")
                .toLowerCase(Locale.ROOT);
    }

    private Map<EnumColumn, Set<String>> expectedEnumValues() {
        Set<String> operationalSources = Set.of(
                SourceSystem.LIVESKLAD.name(), SourceSystem.MANUAL.name()
        );
        return Map.ofEntries(
                Map.entry(new EnumColumn("integration_connections", "source_system"), Set.of(
                        SourceSystem.LIVESKLAD.name(),
                        SourceSystem.AMOCRM.name(),
                        SourceSystem.AI.name()
                )),
                Map.entry(new EnumColumn("stores", "source_system"), operationalSources),
                enumColumn("app_users", "role", UserRole.class),
                enumColumn("sync_runs", "source_system", SourceSystem.class),
                enumColumn("sync_runs", "trigger_type", SyncTriggerType.class),
                enumColumn("sync_runs", "sync_scope", SyncScope.class),
                enumColumn("sync_runs", "status", SyncStatus.class),
                enumColumn("raw_record_versions", "source_system", SourceSystem.class),
                enumColumn("raw_record_versions", "normalization_status", NormalizationStatus.class),
                Map.entry(new EnumColumn("employees", "source_system"), operationalSources),
                Map.entry(new EnumColumn("cash_registers", "source_system"), operationalSources),
                Map.entry(new EnumColumn("source_product_groups", "source_system"), operationalSources),
                Map.entry(new EnumColumn("products", "source_system"), operationalSources),
                enumColumn("products", "source_kind", ProductSourceKind.class),
                enumColumn("analytics_categories", "category_kind", AnalyticsCategoryKind.class),
                enumColumn("analytics_categories", "device_family", DeviceFamily.class),
                enumColumn(
                        "analytics_categories", "attach_denominator_code",
                        AttachDenominatorCode.class
                ),
                enumColumn(
                        "product_category_assignments", "condition_type",
                        ProductConditionType.class
                ),
                enumColumn(
                        "product_category_assignments", "assignment_source",
                        CategoryAssignmentSource.class
                ),
                Map.entry(new EnumColumn("sales_documents", "source_system"), operationalSources),
                enumColumn("sales_documents", "document_kind", SalesDocumentKind.class),
                enumColumn(
                        "sales_document_items", "condition_type_snapshot",
                        ProductConditionType.class
                ),
                enumColumn("sales_document_items", "cost_quality", CostQuality.class),
                enumColumn("sales_payments", "payment_method", PaymentMethod.class),
                enumColumn("report_snapshots", "period_type", ReportPeriodType.class),
                enumColumn("report_snapshots", "report_type", ReportType.class),
                enumColumn("report_snapshots", "status", ReportStatus.class),
                enumColumn("data_quality_issues", "severity", DataQualitySeverity.class),
                enumColumn("data_quality_issues", "status", DataQualityStatus.class)
        );
    }

    private Map.Entry<EnumColumn, Set<String>> enumColumn(
            String table,
            String column,
            Class<? extends Enum<?>> enumType
    ) {
        Set<String> names = new TreeSet<>();
        for (Enum<?> value : enumType.getEnumConstants()) {
            names.add(value.name());
        }
        return Map.entry(new EnumColumn(table, column), names);
    }

    private Set<String> databaseCheckValues(EnumColumn column) {
        List<String> definitions = jdbcTemplate.queryForList(
                """
                SELECT pg_get_constraintdef(constraint_row.oid)
                FROM pg_constraint constraint_row
                WHERE constraint_row.contype = 'c'
                  AND constraint_row.conrelid = CAST(? AS regclass)
                  AND constraint_row.conname = ?
                """,
                String.class,
                column.table(),
                column.table() + "_" + column.column() + "_check"
        );
        Pattern quotedValue = Pattern.compile("'([^']+)'");
        Set<String> values = new TreeSet<>();
        for (String definition : definitions) {
            Matcher matcher = quotedValue.matcher(definition);
            while (matcher.find()) {
                values.add(matcher.group(1));
            }
        }
        return values;
    }

    private void addColumns(Set<String> target, String[] columns) {
        Collections.addAll(target, columns);
        target.removeIf(column -> column == null || column.isBlank());
    }

    private String unqualifiedName(String tableName) {
        String unquoted = tableName.replace("\"", "");
        int separator = unquoted.lastIndexOf('.');
        return separator < 0 ? unquoted : unquoted.substring(separator + 1);
    }
    private LiveSkladEmployeePayload employeePayload(String externalId, String fullName) {
        ObjectNode rawPayload = objectMapper.createObjectNode();
        rawPayload.put("id", externalId);
        rawPayload.put("name", fullName);
        return new LiveSkladEmployeePayload(externalId, fullName, rawPayload);
    }


    private List<LiveSkladStorePayload> readFixture() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/fixtures/livesklad/shops-response.json")) {
            JsonNode root = objectMapper.readTree(input);
            List<LiveSkladStorePayload> stores = new ArrayList<>();
            for (JsonNode node : root.path("data")) {
                stores.add(new LiveSkladStorePayload(
                        node.path("id").stringValue(),
                        node.path("name").stringValue(),
                        node.path("address").stringValue(),
                        node.path("color").stringValue(),
                        node.deepCopy()
                ));
            }
            return stores;
        }
    }

    private Map<String, List<LiveSkladEmployeePayload>> readEmployeeFixture()
            throws IOException {
        try (InputStream input = getClass().getResourceAsStream(
                "/fixtures/livesklad/employees-by-store-response.json"
        )) {
            JsonNode root = objectMapper.readTree(input);
            Map<String, List<LiveSkladEmployeePayload>> employeesByStore = new TreeMap<>();
            for (String storeId : List.of("store-fixture-1", "store-fixture-2")) {
                List<LiveSkladEmployeePayload> employees = new ArrayList<>();
                for (JsonNode node : root.path(storeId)) {
                    employees.add(new LiveSkladEmployeePayload(
                            node.path("id").stringValue(),
                            node.path("name").stringValue(),
                            node.deepCopy()
                    ));
                }
                employeesByStore.put(storeId, List.copyOf(employees));
            }
            return Map.copyOf(employeesByStore);
        }
    }

    private record SaleParties(
            String storeExternalId,
            String employeeExternalId
    ) {
    }

    private record PaymentAmounts(String cash, String card, String bankTransfer) {
    }
    private record EnumColumn(String table, String column) {
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
        private Map<String, List<LiveSkladEmployeePayload>> employeesByStore = Map.of();
        private Map<String, List<LiveSkladSaleSummaryPayload>> salesByStore = Map.of();
        private Map<String, LiveSkladSaleDetailPayload> saleDetails = Map.of();
        private RuntimeException failure;
        private String failingEmployeeStore;
        private RuntimeException employeeFailure;
        private String failingSaleDetail;
        private RuntimeException saleDetailFailure;

        private List<LiveSkladCashItemPayload> cashItems = List.of();
        private Map<String, List<LiveSkladCashRegisterPayload>>
                cashRegistersByStore = Map.of();
        private Map<String, List<LiveSkladCashTransactionPayload>>
                cashTransactionsByQuery = Map.of();
        private Map<String, LiveSkladReturnDetailPayload> returnDetails = Map.of();
        private String failingReturnDetail;
        private RuntimeException returnDetailFailure;
        void setStores(List<LiveSkladStorePayload> sourceStores) {
            stores = List.copyOf(sourceStores);
            failure = null;
        }

        void setEmployees(
                Map<String, List<LiveSkladEmployeePayload>> sourceEmployeesByStore
        ) {
            employeesByStore = Map.copyOf(sourceEmployeesByStore);
            failingEmployeeStore = null;
            employeeFailure = null;
        }

        void setSales(
                Map<String, List<LiveSkladSaleSummaryPayload>> sourceSalesByStore,
                Map<String, LiveSkladSaleDetailPayload> sourceSaleDetails
        ) {
            salesByStore = Map.copyOf(sourceSalesByStore);
            saleDetails = Map.copyOf(sourceSaleDetails);
            failingSaleDetail = null;
            saleDetailFailure = null;
        }

        void setReturns(
                List<LiveSkladCashItemPayload> sourceCashItems,
                Map<String, List<LiveSkladCashRegisterPayload>> sourceRegisters,
                Map<String, List<LiveSkladCashTransactionPayload>>
                        sourceTransactions,
                Map<String, LiveSkladReturnDetailPayload> sourceDetails
        ) {
            cashItems = List.copyOf(sourceCashItems);
            cashRegistersByStore = Map.copyOf(sourceRegisters);
            cashTransactionsByQuery = Map.copyOf(sourceTransactions);
            returnDetails = Map.copyOf(sourceDetails);
            failingReturnDetail = null;
            returnDetailFailure = null;
        }

        void failReturnDetail(
                String returnExternalId,
                RuntimeException sourceFailure
        ) {
            failingReturnDetail = returnExternalId;
            returnDetailFailure = sourceFailure;
        }

        static String cashQuery(String registerId, String cashItemId) {
            return registerId + "|" + cashItemId;
        }

        void failWith(RuntimeException sourceFailure) {
            failure = sourceFailure;
        }

        void failEmployeesForStore(String storeExternalId, RuntimeException sourceFailure) {
            failingEmployeeStore = storeExternalId;
            employeeFailure = sourceFailure;
        }
        void failSaleDetail(String saleExternalId, RuntimeException sourceFailure) {
            failingSaleDetail = saleExternalId;
            saleDetailFailure = sourceFailure;
        }


        @Override
        public List<LiveSkladStorePayload> fetchStores() {
            if (failure != null) {
                throw failure;
            }
            return stores;
        }

        @Override
        public List<LiveSkladEmployeePayload> fetchEmployees(String storeExternalId) {
            if (failure != null) {
                throw failure;
            }
            if (storeExternalId.equals(failingEmployeeStore)) {
                throw employeeFailure;
            }
            return employeesByStore.getOrDefault(storeExternalId, List.of());
        }
        @Override
        public List<LiveSkladSaleSummaryPayload> fetchSales(
                String storeExternalId,
                Instant start,
                Instant end
        ) {
            if (failure != null) {
                throw failure;
            }
            return salesByStore.getOrDefault(storeExternalId, List.of());
        }

        @Override
        public LiveSkladSaleDetailPayload fetchSaleDetail(String saleExternalId) {
            if (saleExternalId.equals(failingSaleDetail)) {
                throw saleDetailFailure;
            }
            return saleDetails.get(saleExternalId);
        }

        @Override
        public List<LiveSkladCashItemPayload> fetchCashItems() {
            if (failure != null) {
                throw failure;
            }
            return cashItems;
        }

        @Override
        public List<LiveSkladCashRegisterPayload> fetchCashRegisters(
                String storeExternalId
        ) {
            if (failure != null) {
                throw failure;
            }
            return cashRegistersByStore.getOrDefault(
                    storeExternalId,
                    List.of()
            );
        }

        @Override
        public List<LiveSkladCashTransactionPayload> fetchCashTransactions(
                String cashRegisterExternalId,
                String cashItemExternalId,
                Instant start,
                Instant end
        ) {
            if (failure != null) {
                throw failure;
            }
            return cashTransactionsByQuery.getOrDefault(
                    cashQuery(
                            cashRegisterExternalId,
                            cashItemExternalId
                    ),
                    List.of()
            );
        }

        @Override
        public LiveSkladReturnDetailPayload fetchReturnDetail(
                String returnExternalId
        ) {
            if (returnExternalId.equals(failingReturnDetail)) {
                throw returnDetailFailure;
            }
            return returnDetails.get(returnExternalId);
        }

    }
}


