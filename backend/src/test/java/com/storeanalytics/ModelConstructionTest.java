package com.storeanalytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.audit.model.AuditLog;
import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.model.UserRole;
import com.storeanalytics.auth.model.UserStoreAccess;
import com.storeanalytics.auth.model.UserStoreAccessId;
import com.storeanalytics.employee.model.Employee;
import com.storeanalytics.employee.model.EmployeeStoreAssignment;
import com.storeanalytics.employee.model.EmployeeStoreAssignmentId;
import com.storeanalytics.integration.connection.model.IntegrationConnection;
import com.storeanalytics.metrics.model.ReportContent;
import com.storeanalytics.metrics.model.ReportIntegrity;
import com.storeanalytics.metrics.model.ReportRevision;
import com.storeanalytics.metrics.model.ReportType;
import com.storeanalytics.metrics.model.ReportDefinition;
import com.storeanalytics.metrics.model.ReportPeriodType;
import com.storeanalytics.metrics.model.ReportSnapshot;
import com.storeanalytics.metrics.model.ReportStatus;
import com.storeanalytics.product.model.AnalyticsCategory;
import com.storeanalytics.product.model.AnalyticsCategoryKind;
import com.storeanalytics.product.model.AnalyticsCategoryRules;
import com.storeanalytics.product.model.DeviceFamily;
import com.storeanalytics.product.model.InventoryValues;
import com.storeanalytics.product.model.Product;
import com.storeanalytics.product.model.ProductCategoryAssignment;
import com.storeanalytics.product.model.ProductDetails;
import com.storeanalytics.product.model.ProductSourceKind;
import com.storeanalytics.product.model.SourceProductGroup;
import com.storeanalytics.product.model.StoreProductInventory;
import com.storeanalytics.product.model.StoreProductInventoryHistory;
import com.storeanalytics.product.model.StoreProductInventoryId;
import com.storeanalytics.quality.model.DataQualityIssue;
import com.storeanalytics.sales.model.SalesDocument;
import com.storeanalytics.sales.model.SalesDocumentDetails;
import com.storeanalytics.sales.model.SalesDocumentItem;
import com.storeanalytics.sales.model.SalesDocumentKind;
import com.storeanalytics.sales.model.SalesItemAmounts;
import com.storeanalytics.sales.model.SalesPayment;
import com.storeanalytics.store.model.CashRegister;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.model.StoreSchedule;
import com.storeanalytics.sync.model.RawRecordVersion;
import com.storeanalytics.sync.model.SourceSystem;
import com.storeanalytics.sync.model.SyncJob;
import com.storeanalytics.sync.model.SyncRun;
import com.storeanalytics.sync.model.SyncRunError;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Version;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.junit.jupiter.api.Test;

class ModelConstructionTest {

    private static final List<Class<?>> ENTITY_TYPES = List.of(
            IntegrationConnection.class,
            Store.class,
            AppUser.class,
            UserStoreAccess.class,
            SyncJob.class,
            SyncRun.class,
            SyncRunError.class,
            RawRecordVersion.class,
            Employee.class,
            EmployeeStoreAssignment.class,
            CashRegister.class,
            SourceProductGroup.class,
            Product.class,
            AnalyticsCategory.class,
            ProductCategoryAssignment.class,
            StoreProductInventory.class,
            StoreProductInventoryHistory.class,
            SalesDocument.class,
            SalesDocumentItem.class,
            SalesPayment.class,
            ReportSnapshot.class,
            DataQualityIssue.class,
            AuditLog.class
    );

    @Test
    void everyEntityHasJpaConstructorAndApplicationCreationPath() throws NoSuchMethodException {
        assertThat(ENTITY_TYPES).hasSize(23);
        for (Class<?> entityType : ENTITY_TYPES) {
            assertThat(entityType).hasAnnotation(Entity.class);
            assertThat(Modifier.isProtected(entityType.getDeclaredConstructor().getModifiers()))
                    .as("%s JPA constructor must be protected", entityType.getSimpleName())
                    .isTrue();

            boolean hasPublicConstructor = List.of(entityType.getConstructors()).stream()
                    .anyMatch(constructor -> constructor.getParameterCount() > 0);
            boolean hasPublicFactory = List.of(entityType.getDeclaredMethods()).stream()
                    .anyMatch(method -> Modifier.isPublic(method.getModifiers())
                            && Modifier.isStatic(method.getModifiers())
                            && method.getReturnType() == entityType
                            && method.getParameterCount() > 0);

            assertThat(hasPublicConstructor || hasPublicFactory)
                    .as("%s must have an application creation path", entityType.getSimpleName())
                    .isTrue();
        }
    }

    @Test
    void databaseManagedTimestampsAndMutableEntitiesHaveRequiredJpaContracts() {
        int mutableEntityCount = 0;
        for (Class<?> entityType : ENTITY_TYPES) {
            List<Field> fields = fieldsInHierarchy(entityType);
            boolean hasUpdatedAt = fields.stream()
                    .anyMatch(field -> field.getName().equals("updatedAt"));
            if (hasUpdatedAt) {
                mutableEntityCount++;
                assertThat(fields)
                        .as("%s must use optimistic locking", entityType.getSimpleName())
                        .anyMatch(field -> field.isAnnotationPresent(Version.class));
            }
            fields.stream()
                    .filter(field -> field.getType() == Instant.class)
                    .filter(field -> field.isAnnotationPresent(Column.class))
                    .filter(field -> !field.getAnnotation(Column.class).insertable())
                    .forEach(field -> assertThat(field.isAnnotationPresent(Generated.class))
                            .as("%s.%s must fetch its DB-generated value",
                                    entityType.getSimpleName(), field.getName())
                            .isTrue());
        }
        assertThat(mutableEntityCount).isEqualTo(14);
    }

    @Test
    void compositeIdsAreConstructibleValueObjects() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertThat(new UserStoreAccessId(first, second))
                .isEqualTo(new UserStoreAccessId(first, second));
        assertThat(new EmployeeStoreAssignmentId(first, second))
                .isEqualTo(new EmployeeStoreAssignmentId(first, second));
        assertThat(new StoreProductInventoryId(first, second))
                .isEqualTo(new StoreProductInventoryId(first, second));

        assertThatThrownBy(() -> new UserStoreAccessId(null, second))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void zeroCostIsExpectedOnlyForNonMaterialAnalyticsCategories() {
        assertThat(category(AnalyticsCategoryKind.SERVICE).permitsZeroCost()).isTrue();
        assertThat(category(AnalyticsCategoryKind.WARRANTY).permitsZeroCost()).isTrue();
        assertThat(category(AnalyticsCategoryKind.PROTECTION).permitsZeroCost()).isTrue();
        assertThat(category(AnalyticsCategoryKind.DEVICE).permitsZeroCost()).isFalse();
        assertThat(category(AnalyticsCategoryKind.ACCESSORY).permitsZeroCost()).isFalse();
        assertThat(category(AnalyticsCategoryKind.OTHER).permitsZeroCost()).isFalse();
        assertThat(category(AnalyticsCategoryKind.EXCLUDED).permitsZeroCost()).isFalse();
    }

    private AnalyticsCategory category(AnalyticsCategoryKind kind) {
        return new AnalyticsCategory(kind.name(), kind.name(), null, new AnalyticsCategoryRules(
                kind, DeviceFamily.NONE, false, false, false, null, false
        ));
    }

    @Test
    void databaseInvariantsAreRejectedBeforePersistence() {
        assertThatThrownBy(() -> new AppUser(" ", "hash", "Manager", UserRole.MANAGER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Employee.manual(null, "Manual employee"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new SalesItemAmounts(
                BigDecimal.ZERO,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ZERO,
                BigDecimal.ONE,
                null
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new AnalyticsCategory(
                "CATEGORY",
                "Category",
                null,
                new AnalyticsCategoryRules(
                        AnalyticsCategoryKind.ACCESSORY,
                        DeviceFamily.NONE,
                        false,
                        false,
                        true,
                        null,
                        true
                )
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ReportSnapshot(
                null,
                new ReportDefinition(
                        ReportType.ANNUAL,
                        ReportPeriodType.CUSTOM,
                        LocalDate.of(2026, 2, 2),
                        LocalDate.of(2026, 2, 1),
                        ReportStatus.DRAFT,
                        "formula-v1",
                        "classification-v1"
                ),
                new ReportContent(
                        new ReportIntegrity(null, "0".repeat(64)),
                        "{}",
                        Instant.parse("2026-02-02T00:00:00Z"),
                        null,
                        null,
                        null
                ),
                new ReportRevision(1, null, null, null, 1)
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void databaseTypesAreValidatedBeforePersistence() {
        assertThatThrownBy(() -> new SalesItemAmounts(
                BigDecimal.ONE,
                new BigDecimal("1.001"),
                BigDecimal.ONE,
                BigDecimal.ZERO,
                BigDecimal.ONE,
                null
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new InventoryValues(
                new BigDecimal("10000000000000000.000"),
                null,
                null,
                null
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ReportContent(
                new ReportIntegrity(null, "0".repeat(64)),
                "not-json",
                Instant.parse("2026-02-02T00:00:00Z"),
                null,
                null,
                null
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new StoreSchedule(
                "Invalid/Timezone",
                LocalTime.MIDNIGHT,
                LocalTime.of(10, 0),
                LocalTime.of(21, 0)
        )).isInstanceOf(IllegalArgumentException.class);

        SalesDocumentDetails returnDetails = new SalesDocumentDetails(
                "RETURN-1",
                SalesDocumentKind.RETURN,
                "RETURN",
                "COMPLETED",
                Instant.parse("2026-02-02T00:00:00Z"),
                LocalDate.of(2026, 2, 2),
                null
        );
        assertThatThrownBy(() -> returnDetails.validateOriginalDocument(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void employeeLifecycleValidatesSourceAndRejectsStaleUpdates() {
        Instant currentTimestamp = Instant.parse("2026-02-02T00:00:02Z");
        Employee employee = Employee.fromLiveSklad(
                connection("employee-lifecycle"),
                "employee-lifecycle",
                "Current name",
                currentTimestamp
        );

        assertThat(employee.updateFromLiveSklad(
                "Stale name",
                false,
                currentTimestamp.minusSeconds(1)
        )).isFalse();
        assertThat(employee.getFullName()).isEqualTo("Current name");
        assertThat(employee.isActive()).isTrue();

        assertThat(employee.updateFromLiveSklad(
                "Updated name",
                false,
                currentTimestamp.plusSeconds(1)
        )).isTrue();
        assertThat(employee.getFullName()).isEqualTo("Updated name");
        assertThat(employee.isActive()).isFalse();

        Employee manualEmployee = Employee.manual("manual-employee", "Manual employee");
        assertThat(manualEmployee.updateManual("Manual employee updated", false)).isTrue();
        assertThatThrownBy(() -> manualEmployee.updateFromLiveSklad(
                "Invalid source update",
                true,
                currentTimestamp
        )).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void crossEntityConnectionsAreValidatedBeforePersistence() {
        IntegrationConnection firstConnection = connection("first");
        IntegrationConnection secondConnection = connection("second");
        Store store = Store.fromLiveSklad(
                firstConnection, "store-1", "Store", "Address"
        );

        assertThatThrownBy(() -> CashRegister.fromLiveSklad(
                secondConnection, store, "cash-1", "Cash"
        )).isInstanceOf(IllegalArgumentException.class);

        Employee employee = Employee.fromLiveSklad(
                secondConnection, "employee-1", "Employee", null
        );
        assertThatThrownBy(() -> new EmployeeStoreAssignment(employee, store, true))
                .isInstanceOf(IllegalArgumentException.class);

        Product product = Product.fromLiveSklad(
                secondConnection,
                "product-1",
                new ProductDetails(
                        null, null, null, "Product", ProductSourceKind.PRODUCT, null
                )
        );
        assertThatThrownBy(() -> new StoreProductInventory(
                store,
                product,
                new InventoryValues(BigDecimal.ONE, null, null, null),
                null
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void syncRunCanOnlyReachOneTerminalState() {
        SyncRun syncRun = SyncRun.startStoreSync(
                connection("sync"), Instant.parse("2026-02-02T00:00:00Z")
        );
        syncRun.complete(1, 1, 0, 0, Instant.parse("2026-02-02T00:00:01Z"));

        assertThatThrownBy(() -> syncRun.fail(
                1, "too late", Instant.parse("2026-02-02T00:00:02Z")
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private List<Field> fieldsInHierarchy(Class<?> type) {
        List<Field> fields = new java.util.ArrayList<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            fields.addAll(List.of(current.getDeclaredFields()));
            current = current.getSuperclass();
        }
        return fields;
    }

    private IntegrationConnection connection(String key) {
        return new IntegrationConnection(
                key,
                SourceSystem.LIVESKLAD,
                key,
                "https://example.invalid",
                "env:TEST_CREDENTIALS"
        );
    }
}
