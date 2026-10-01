package com.storeanalytics.sync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.integration.livesklad.dto.LiveSkladReturnDetailPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladReturnPositionPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladSaleDetailPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladSalePositionPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladSaleSummaryPayload;
import com.storeanalytics.product.model.CatalogCompatibilityEvidence.Coverage;
import com.storeanalytics.product.model.CatalogCompatibilityEvidence.Target;
import com.storeanalytics.product.repository.CatalogCompatibilityRepository;
import com.storeanalytics.product.service.CatalogProductReviewQueueService;
import com.storeanalytics.product.service.CatalogProductReviewDecisionService;
import com.storeanalytics.product.service.CatalogProductReviewDecision;
import com.storeanalytics.product.model.ProductConditionType;
import com.storeanalytics.salary.model.PayrollCategoryCode;
import com.storeanalytics.product.service.CatalogCompatibilityRecords.Action;
import com.storeanalytics.product.service.CatalogCompatibilityRecords.Origin;
import com.storeanalytics.product.service.CatalogCompatibilityRecords.Request;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.repository.StoreRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Exercises the real Spring transaction, JPA flush and JDBC snapshot bridge, not a mocked EntityManager. */
@SpringBootTest(properties = {"app.catalog-classification.activate-from=2025-12-31T22:00:00Z",
        "app.catalog-compatibility.snapshots-enabled=true",
        "app.catalog-compatibility.snapshots-from=2025-12-31T22:00:00Z"
})
@org.springframework.context.annotation.Import(CatalogRoleSyncIntegrationTest.BoundaryFixture.class)
@Testcontainers
class CatalogRoleSyncIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    private static final String NAME = "Кабель для Apple Watch";
    private static final BigDecimal PRICE = new BigDecimal("100.00");
    private static final BigDecimal COST = new BigDecimal("50.00");
    @Autowired
    private SalesSyncPersistence sales;
    @Autowired
    private ReturnSyncPersistence returns;
    @Autowired
    private CatalogCompatibilityRepository compatibility;
    @Autowired
    private CatalogProductReviewQueueService productReviews;
    @Autowired
    private CatalogProductReviewDecisionService reviewDecisions;
    @Autowired
    private StoreRepository stores;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper mapper;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void syncCapturesOnceAndReturnInheritsDespiteRevocation() {
        var fixture = fixture();
        var source = source(fixture, Instant.now(), NAME, false);
        sync(fixture, source);
        String initial = snapshot(fixture.sale() + "-item");
        assertThat(initial).contains("ACCESSORY_APPLE_WATCH", "CHARGER_CABLE", "SALE_PROJECTION");
        String financial = financial(fixture.sale() + "-item");
        sync(fixture, source);
        assertThat(snapshot(fixture.sale() + "-item")).isEqualTo(initial);
        assertThat(financial(fixture.sale() + "-item")).isEqualTo(financial);

        compatibility.append(compatibility.observe(fixture.product(), false), 2,
                new Request(Action.REVOKE, Coverage.UNDETERMINED, List.of(), "Synthetic revocation",
                        Origin.DIRECT_REVIEW, null, null), fixture.actor());
        var returned = returned(fixture);
        returns.synchronizeTargeted(fixture.run(), fixture.store(), returned);
        String inherited = snapshot(fixture.sale() + "-return-item");
        assertThat(inherited).contains("ACCESSORY_APPLE_WATCH", "ORIGINAL_SALE", "CURRENT");
        returns.synchronizeTargeted(fixture.run(), fixture.store(), returned);
        assertThat(snapshot(fixture.sale() + "-return-item")).isEqualTo(inherited);
        assertThat(snapshot(fixture.sale() + "-item")).isEqualTo(initial);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_sale_role_snapshots s "
                + "JOIN sales_document_items i ON i.id = s.item_id WHERE i.product_id = ?",
                Integer.class, fixture.product())).isEqualTo(2);
    }

    @Test
    void syncCorrectionInvalidatesButNeverRewritesInitialEvidence() {
        var fixture = fixture();
        Instant at = Instant.now();
        sync(fixture, source(fixture, at, NAME, false));
        String before = snapshot(fixture.sale() + "-item");
        sync(fixture, source(fixture, at, "Кабель USB-C", false));
        assertThat(jdbc.queryForObject("SELECT product_name_snapshot FROM sales_document_items "
                + "WHERE external_id = ?", String.class, fixture.sale() + "-item")).isEqualTo("Кабель USB-C");
        assertThat(snapshot(fixture.sale() + "-item")).contains("STALE")
                .isEqualTo(before.replace("CURRENT", "STALE"));
    }

    @Test
    void oldSaleAndItsReturnDoNotBorrowCurrentConfirmation() {
        var fixture = fixture();
        sync(fixture, source(fixture, Instant.parse("2025-12-01T10:00:00Z"), NAME, false));
        assertThat(snapshot(fixture.sale() + "-item")).isNull();
        returns.synchronizeTargeted(fixture.run(), fixture.store(), returned(fixture));
        assertThat(snapshot(fixture.sale() + "-return-item"))
                .contains("LEGACY_RETURN", "DEFER_TO_EXISTING").doesNotContain("ACCESSORY_APPLE_WATCH");
    }

    @Test
    void failureAfterFirstJpaFlushRollsBackFactsAndSnapshotTogether() {
        var fixture = fixture();
        assertThatThrownBy(() -> sync(fixture, source(fixture, Instant.now(), NAME, true)))
                .isInstanceOf(RuntimeException.class);
        assertThat(snapshot(fixture.sale() + "-item")).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales_documents WHERE external_id = ?",
                Integer.class, fixture.sale())).isZero();
    }

    @Test
    void oldSaleKeepsCategoryOnResyncAndLinkedReturnWhileNewSaleUsesNewRules() {
        var old = fixture();
        Instant before = Instant.parse("2025-12-31T21:59:59Z");
        sync(old, source(old, before, "AirPods Pro", false));
        String oldFinancial = financial(old.sale() + "-item");
        assertThat(category(old.sale() + "-item")).isEqualTo("PODS_WATCH_OTHER_DEVICE");

        // A changed catalog name must not reclassify a saved historical sale.
        sync(old, source(old, before, "Apple iPhone 17 256GB", false));
        assertThat(financial(old.sale() + "-item")).isEqualTo(oldFinancial);
        returns.synchronizeTargeted(old.run(), old.store(), returned(old));
        assertThat(category(old.sale() + "-return-item")).isEqualTo("PODS_WATCH_OTHER_DEVICE");

        var current = fixture();
        sync(current, source(current, Instant.parse("2025-12-31T22:00:00Z"), "AirPods Pro", false));
        assertThat(category(current.sale() + "-item")).isEqualTo("HEADPHONES_APPLE");
    }

    @Test
    void productFirstSeenAfterBoundaryNeedsManualAnalyticalCategory() {
        var current = fixture();
        jdbc.update("UPDATE products SET created_at = now() WHERE id = ?", current.product());
        sync(current, source(current, Instant.now(), "AirPods Pro", false));
        assertThat(category(current.sale() + "-item")).isEqualTo("UNMAPPED");
        var review = productReviews.list(100);
        assertThat(review.items()).anySatisfy(item -> {
            assertThat(item.productId()).isEqualTo(current.product());
            assertThat(item.hasUnmappedSales()).isTrue();
            assertThat(item.suggestedAnalyticsCategoryCode()).isEqualTo("HEADPHONES_APPLE");
            assertThat(item.assignedPayrollCategoryCode()).isNull();
        });
    }

    @Test
    void productCreatedInsideSaleSyncAlsoWaitsForReview() {
        var base = fixture();
        UUID newProductId = UUID.randomUUID();
        String saleId = UUID.randomUUID().toString();
        Instant at = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var position = new LiveSkladSalePositionPayload(saleId + "-item",
                newProductId.toString(), "new-product-code", null, "AirPods Pro", false,
                BigDecimal.ONE, PRICE, PRICE, COST);
        var raw = mapper.createObjectNode().put("id", saleId);
        var summary = new LiveSkladSaleSummaryPayload(saleId, "New product sale", at,
                "sale", PRICE, PRICE, COST, raw);
        var detail = new LiveSkladSaleDetailPayload(saleId, "New product sale", at,
                Instant.now(), "sale", base.store().getExternalId(), null, null, PRICE,
                BigDecimal.ZERO, BigDecimal.ZERO, List.of(position), raw);
        sales.synchronize(base.run(), new SalesSyncPeriod(at.minusSeconds(60), at.plusSeconds(60)),
                List.of(new StoreSalesBatch(base.store(),
                        List.of(new LiveSkladSaleSource(summary, detail)))));

        assertThat(category(saleId + "-item")).isEqualTo("UNMAPPED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM products WHERE external_id = ?",
                Integer.class, newProductId.toString())).isOne();
        assertThat(productReviews.list(100).items()).anySatisfy(item -> {
            assertThat(item.externalId()).isEqualTo(newProductId.toString());
            assertThat(item.suggestedAnalyticsCategoryCode()).isEqualTo("HEADPHONES_APPLE");
            assertThat(item.assignedPayrollCategoryCode()).isNull();
        });
    }

    @Test
    void managerDecisionAtomicallyClassifiesNewSaleAndPayroll() {
        var current = fixture();
        jdbc.update("UPDATE products SET created_at = now() WHERE id = ?", current.product());
        sync(current, source(current, Instant.now(), "AirPods Pro", false));
        returns.synchronizeTargeted(current.run(), current.store(), returned(current));
        assertThat(category(current.sale() + "-return-item")).isEqualTo("UNMAPPED");
        var item = productReviews.list(100).items().stream()
                .filter(value -> value.productId().equals(current.product()))
                .findFirst().orElseThrow();
        var result = reviewDecisions.decide(current.product(), new CatalogProductReviewDecision(
                item.productVersion(), "HEADPHONES_APPLE", ProductConditionType.NOT_APPLICABLE,
                PayrollCategoryCode.TECH_TIER_2, "Reviewed synthetic product"), current.actor());
        assertThat(result.reclassifiedItems()).isEqualTo(2);
        assertThat(category(current.sale() + "-return-item")).isEqualTo("HEADPHONES_APPLE");
        assertThat(category(current.sale() + "-item")).isEqualTo("HEADPHONES_APPLE");
        assertThat(jdbc.queryForObject("SELECT payroll_category_code FROM product_payroll_category_assignments "
                + "WHERE product_id = ?", String.class, current.product())).isEqualTo("TECH_TIER_2");
        assertThat(jdbc.queryForObject("SELECT valid_from FROM product_category_assignments "
                + "WHERE product_id = ?", java.sql.Timestamp.class, current.product()).toInstant())
                .isEqualTo(Instant.parse("2025-12-31T22:00:00Z"));
        assertThat(jdbc.queryForObject("SELECT valid_from FROM product_payroll_category_assignments "
                + "WHERE product_id = ?", java.time.LocalDate.class, current.product()))
                .isEqualTo(java.time.LocalDate.of(2026, 1, 1));
        assertThat(productReviews.list(100).items()).noneMatch(value ->
                value.productId().equals(current.product()));
    }

    @Test
    void singleGlobalDecisionReconcilesNewProductSalesInBothStores() {
        var first = fixture();
        jdbc.update("UPDATE products SET created_at = now() WHERE id = ?", first.product());
        UUID secondStoreId = UUID.randomUUID();
        UUID connection = jdbc.queryForObject("SELECT connection_id FROM stores WHERE id = ?",
                UUID.class, first.store().getId());
        jdbc.update("INSERT INTO stores(id, connection_id, external_id, name) "
                + "VALUES (?, ?, ?, 'Second synthetic store')",
                secondStoreId, connection, secondStoreId.toString());
        var second = new Fixture(first.actor(), first.product(),
                stores.findById(secondStoreId).orElseThrow(), first.run(),
                UUID.randomUUID().toString());
        sync(first, source(first, Instant.now(), "AirPods Pro", false));
        sync(second, source(second, Instant.now(), "AirPods Pro", false));
        assertThat(category(first.sale() + "-item")).isEqualTo("UNMAPPED");
        assertThat(category(second.sale() + "-item")).isEqualTo("UNMAPPED");

        var item = productReviews.list(100).items().stream()
                .filter(value -> value.productId().equals(first.product()))
                .findFirst().orElseThrow();
        var result = reviewDecisions.decide(first.product(), new CatalogProductReviewDecision(
                item.productVersion(), "HEADPHONES_APPLE", ProductConditionType.NOT_APPLICABLE,
                PayrollCategoryCode.TECH_TIER_2, "Reviewed for both stores"), first.actor());
        assertThat(result.reclassifiedItems()).isEqualTo(2);
        assertThat(result.affectedStoreIds()).containsExactlyInAnyOrder(
                first.store().getId(), secondStoreId);
        assertThat(category(first.sale() + "-item")).isEqualTo("HEADPHONES_APPLE");
        assertThat(category(second.sale() + "-item")).isEqualTo("HEADPHONES_APPLE");

        var backdated = new Fixture(first.actor(), first.product(), second.store(), first.run(),
                UUID.randomUUID().toString());
        sync(backdated, source(backdated, Instant.parse("2026-01-02T12:00:00Z"), "AirPods Pro", false));
        assertThat(category(backdated.sale() + "-item")).isEqualTo("HEADPHONES_APPLE");
    }

    @Test
    void decisionForNewSaleNeverReclassifiesSaleOrLinkedReturnBeforeBoundary() {
        var historical = fixture();
        jdbc.update("UPDATE products SET created_at = now() WHERE id = ?", historical.product());
        sync(historical, source(historical, Instant.parse("2025-12-31T21:59:59Z"),
                "AirPods Pro", false));
        returns.synchronizeTargeted(historical.run(), historical.store(), returned(historical));
        assertThat(category(historical.sale() + "-item")).isEqualTo("PODS_WATCH_OTHER_DEVICE");
        assertThat(category(historical.sale() + "-return-item")).isEqualTo("PODS_WATCH_OTHER_DEVICE");

        var current = new Fixture(historical.actor(), historical.product(), historical.store(),
                historical.run(), UUID.randomUUID().toString());
        sync(current, source(current, Instant.now(), "AirPods Pro", false));
        var item = productReviews.list(100).items().stream()
                .filter(value -> value.productId().equals(current.product()))
                .findFirst().orElseThrow();
        reviewDecisions.decide(current.product(), new CatalogProductReviewDecision(
                item.productVersion(), "HEADPHONES_APPLE", ProductConditionType.NOT_APPLICABLE,
                PayrollCategoryCode.TECH_TIER_2, "Prospective-only review"), current.actor());

        assertThat(category(current.sale() + "-item")).isEqualTo("HEADPHONES_APPLE");
        assertThat(category(historical.sale() + "-item")).isEqualTo("PODS_WATCH_OTHER_DEVICE");
        assertThat(category(historical.sale() + "-return-item")).isEqualTo("PODS_WATCH_OTHER_DEVICE");
    }

    @Test
    void priorAnalyticalChoiceCanBeCompletedWithPayrollWithoutDuplicatingIt() {
        var current = fixture();
        jdbc.update("UPDATE products SET created_at = now() WHERE id = ?", current.product());
        sync(current, source(current, Instant.now(), "AirPods Pro", false));
        Instant firstSale = jdbc.queryForObject("""
                SELECT document.occurred_at FROM sales_documents document
                WHERE document.external_id = ?
                """, Instant.class, current.sale());
        jdbc.update("""
                INSERT INTO product_category_assignments(product_id, analytics_category_id,
                    condition_type, assignment_source, valid_from, assigned_by, change_reason)
                SELECT ?, category.id, 'NOT_APPLICABLE', 'MANUAL', ?, ?, 'Prior reviewed choice'
                FROM analytics_categories category WHERE category.code = 'HEADPHONES_APPLE'
                """, current.product(), java.sql.Timestamp.from(firstSale), current.actor());
        var item = productReviews.list(100).items().stream()
                .filter(value -> value.productId().equals(current.product()))
                .findFirst().orElseThrow();
        assertThat(item.assignedAnalyticsCategoryCode()).isEqualTo("HEADPHONES_APPLE");
        assertThat(item.assignedConditionType()).isEqualTo("NOT_APPLICABLE");
        reviewDecisions.decide(current.product(), new CatalogProductReviewDecision(
                item.productVersion(), "HEADPHONES_APPLE", ProductConditionType.NOT_APPLICABLE,
                PayrollCategoryCode.TECH_TIER_2, "Complete prior choice"), current.actor());
        assertThat(category(current.sale() + "-item")).isEqualTo("HEADPHONES_APPLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_category_assignments "
                + "WHERE product_id = ?", Integer.class, current.product())).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_payroll_category_assignments "
                + "WHERE product_id = ?", Integer.class, current.product())).isOne();
        assertThat(productReviews.list(100).items()).noneMatch(value ->
                value.productId().equals(current.product()));
    }

    @Test
    void priorPayrollChoiceCanBeCompletedWithAnalyticsWithoutDuplicatingIt() {
        var current = fixture();
        jdbc.update("UPDATE products SET created_at = now() WHERE id = ?", current.product());
        sync(current, source(current, Instant.now(), "AirPods Pro", false));
        java.time.LocalDate firstSale = jdbc.queryForObject("""
                SELECT document.business_date FROM sales_documents document
                WHERE document.external_id = ?
                """, java.time.LocalDate.class, current.sale());
        jdbc.update("""
                INSERT INTO product_payroll_category_assignments(product_id, payroll_category_code,
                    valid_from, assigned_by, change_reason)
                VALUES (?,'TECH_TIER_2',?,?,'Prior reviewed choice')
                """, current.product(), firstSale, current.actor());
        var item = productReviews.list(100).items().stream()
                .filter(value -> value.productId().equals(current.product()))
                .findFirst().orElseThrow();
        assertThat(item.assignedPayrollCategoryCode()).isEqualTo("TECH_TIER_2");
        reviewDecisions.decide(current.product(), new CatalogProductReviewDecision(
                item.productVersion(), "HEADPHONES_APPLE", ProductConditionType.NOT_APPLICABLE,
                PayrollCategoryCode.TECH_TIER_2, "Complete prior choice"), current.actor());
        assertThat(category(current.sale() + "-item")).isEqualTo("HEADPHONES_APPLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_category_assignments "
                + "WHERE product_id = ?", Integer.class, current.product())).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_payroll_category_assignments "
                + "WHERE product_id = ?", Integer.class, current.product())).isOne();
    }

    @Test
    void invalidPayrollChoiceRollsBackAnalyticalDecisionAndSaleChange() {
        var current = fixture();
        jdbc.update("UPDATE products SET created_at = now() WHERE id = ?", current.product());
        sync(current, source(current, Instant.now(), "AirPods Pro", false));
        var item = productReviews.list(100).items().stream()
                .filter(value -> value.productId().equals(current.product()))
                .findFirst().orElseThrow();
        assertThatThrownBy(() -> reviewDecisions.decide(current.product(),
                new CatalogProductReviewDecision(item.productVersion(), "HEADPHONES_APPLE",
                        ProductConditionType.NOT_APPLICABLE, PayrollCategoryCode.UNMAPPED,
                        "Invalid payroll role"), current.actor()))
                .isInstanceOf(com.storeanalytics.common.exception.InvalidRequestException.class);
        assertThat(category(current.sale() + "-item")).isEqualTo("UNMAPPED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_category_assignments "
                + "WHERE product_id = ?", Integer.class, current.product())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_payroll_category_assignments "
                + "WHERE product_id = ?", Integer.class, current.product())).isZero();
    }

    @Test
    void classifiedAccessoryWithImmutableOldSnapshotRequiresSaleReview() {
        var current = fixture();
        jdbc.update("UPDATE products SET created_at = now() WHERE id = ?", current.product());
        sync(current, source(current, Instant.now(), NAME, false));
        var item = productReviews.list(100).items().stream()
                .filter(value -> value.productId().equals(current.product()))
                .findFirst().orElseThrow();
        reviewDecisions.decide(current.product(), new CatalogProductReviewDecision(
                item.productVersion(), "CHARGER_CABLE", ProductConditionType.NOT_APPLICABLE,
                PayrollCategoryCode.ACCESSORY, "Reviewed synthetic cable"), current.actor());
        assertThat(snapshot(current.sale() + "-item")).contains("STALE");
        assertThat(jdbc.queryForObject("SELECT catalog_role_pending_issue(i.id) "
                + "FROM sales_document_items i WHERE i.external_id = ?", String.class,
                current.sale() + "-item")).isEqualTo("CATALOG_ROLE_REVIEW_CHARGER_CABLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM case_attach_review_items r "
                + "JOIN sales_document_items i ON i.id = r.source_item_id "
                + "WHERE i.external_id = ?", Integer.class, current.sale() + "-item")).isOne();
    }

    private String category(String external) {
        return jdbc.queryForObject("SELECT c.code FROM sales_document_items i "
                + "JOIN analytics_categories c ON c.id = i.analytics_category_id WHERE i.external_id = ?",
                String.class, external);
    }

    private void sync(Fixture fixture, LiveSkladSaleSource source) {
        Instant at = source.summary().occurredAt();
        sales.synchronize(fixture.run(), new SalesSyncPeriod(at.minusSeconds(60), at.plusSeconds(60)),
                List.of(new StoreSalesBatch(fixture.store(), List.of(source))));
    }

    private LiveSkladSaleSource source(Fixture fixture, Instant at, String name, boolean invalidSecond) {
        // Use database-representable source timestamps so repeated observations are not spuriously older.
        at = at.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var position = new LiveSkladSalePositionPayload(fixture.sale() + "-item", fixture.product().toString(),
                "synthetic", null, name, false, BigDecimal.ONE, PRICE, PRICE, COST);
        var invalid = new LiveSkladSalePositionPayload(fixture.sale() + "-invalid", fixture.product().toString(),
                "synthetic", null, name, false, BigDecimal.ONE.negate(), PRICE, PRICE, COST);
        var raw = mapper.createObjectNode().put("id", fixture.sale()).put("name", name);
        var summary = new LiveSkladSaleSummaryPayload(fixture.sale(), "Synthetic", at, "sale",
                PRICE, PRICE, COST, raw);
        var detail = new LiveSkladSaleDetailPayload(fixture.sale(), "Synthetic", at, Instant.now(), "sale",
                fixture.store().getExternalId(), null, null, PRICE, BigDecimal.ZERO, BigDecimal.ZERO,
                invalidSecond ? List.of(position, invalid) : List.of(position), raw);
        return new LiveSkladSaleSource(summary, detail);
    }

    private LiveSkladReturnSource returned(Fixture fixture) {
        String id = fixture.sale() + "-return";
        var position = new LiveSkladReturnPositionPayload(id + "-item", fixture.sale() + "-item",
                fixture.product().toString(), "synthetic", null, NAME, false,
                BigDecimal.ONE, PRICE, PRICE, COST);
        var detail = new LiveSkladReturnDetailPayload(id, "Synthetic return", Instant.now(), Instant.now(),
                "saleReturn", fixture.store().getExternalId(), null, fixture.sale(), PRICE,
                BigDecimal.ZERO, BigDecimal.ZERO, List.of(position), mapper.createObjectNode().put("id", id));
        return new LiveSkladReturnSource(List.of(), detail);
    }

    private String snapshot(String external) {
        return jdbc.query("SELECT to_jsonb(s)::text FROM catalog_sale_role_snapshot_states s "
                + "JOIN sales_document_items i ON i.id = s.item_id WHERE i.external_id = ?",
                (row, index) -> row.getString(1), external).stream().findFirst().orElse(null);
    }

    private String financial(String external) {
        return jdbc.queryForObject("SELECT jsonb_build_array(analytics_category_id,quantity,unit_price,"
                + "net_amount,cost_amount,cost_quality,condition_type_snapshot)::text "
                + "FROM sales_document_items WHERE external_id = ?", String.class, external);
    }

    private Fixture fixture() {
        UUID actor = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        UUID store = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        UUID connection = jdbc.queryForObject("SELECT id FROM integration_connections "
                + "WHERE connection_key = 'livesklad-default'", UUID.class);
        jdbc.update("INSERT INTO app_users(id,email,password_hash,display_name,role,password_change_required) "
                + "VALUES (?,?,'synthetic-not-a-credential','Synthetic actor','ADMIN',false)",
                actor, actor + "@example.invalid");
        jdbc.update("INSERT INTO stores(id,connection_id,external_id,name) VALUES (?,?,?,'Synthetic store')",
                store, connection, store.toString());
        jdbc.update("INSERT INTO sync_runs(id,connection_id,source_system,trigger_type,sync_scope,status) "
                + "VALUES (?,?,'LIVESKLAD','MANUAL','SALES','RUNNING')", run, connection);
        jdbc.update("INSERT INTO products(id,connection_id,external_id,code,name,source_kind) "
                + "VALUES (?,?,?,'synthetic',?,'PRODUCT')", product, connection, product.toString(), NAME);
        jdbc.update("UPDATE products SET created_at = '2025-12-01T00:00:00Z' WHERE id = ?", product);
        compatibility.append(compatibility.observe(product, false), 1,
                new Request(Action.CONFIRM, Coverage.EXCLUSIVE, List.of(Target.APPLE_WATCH),
                        "Synthetic confirmation", Origin.DIRECT_REVIEW, null, null), actor);
        return new Fixture(actor, product, stores.findById(store).orElseThrow(), run, UUID.randomUUID().toString());
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class BoundaryFixture {
        @org.springframework.context.annotation.Bean
        org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy fixtureBoundary() {
            return flyway -> {
                flyway.migrate();
                new JdbcTemplate(flyway.getConfiguration().getDataSource()).update("""
                        INSERT INTO catalog_classification_activation(activate_from, recorded_at)
                        VALUES ('2025-12-31T22:00:00Z', '2025-12-01T00:00:00Z')
                        """);
            };
        }
    }

    private record Fixture(UUID actor, UUID product, Store store, UUID run, String sale) { }
}
