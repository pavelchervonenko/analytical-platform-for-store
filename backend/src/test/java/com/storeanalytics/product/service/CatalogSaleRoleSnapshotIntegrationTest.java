package com.storeanalytics.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.storeanalytics.product.model.CatalogCompatibilityEvidence.Coverage;
import com.storeanalytics.product.model.CatalogCompatibilityEvidence.Target;
import com.storeanalytics.product.repository.CatalogCompatibilityRepository;
import com.storeanalytics.product.service.CatalogCompatibilityRecords.Action;
import com.storeanalytics.product.service.CatalogCompatibilityRecords.Origin;
import com.storeanalytics.product.service.CatalogCompatibilityRecords.Request;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class CatalogSaleRoleSnapshotIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    private static NamedParameterJdbcTemplate jdbc;
    private static CatalogCompatibilityRepository compatibility;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        compatibility = new CatalogCompatibilityRepository(jdbc);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Test
    void capturesWatchOverrideOnceWithoutChangingMoneyCategoryOrFinancialFacts() {
        var fixture = fixture();
        UUID sale = item(fixture, null, fixture.confirmedAt());
        String before = factRow(sale);
        capture(sale);
        Map<String, Object> snapshot = snapshot(sale);
        assertThat(snapshot).containsEntry("role", "ACCESSORY_APPLE_WATCH")
                .containsEntry("monetary_category", "CHARGER_CABLE")
                .containsEntry("origin", "SALE_PROJECTION").containsEntry("state", "CURRENT");
        capture(sale);
        assertThat(snapshot(sale)).isEqualTo(snapshot);
        assertThat(factRow(sale)).isEqualTo(before);
        assertThatThrownBy(() -> jdbc.update("UPDATE catalog_sale_role_snapshots SET reason = 'edit' "
                + "WHERE item_id = :id", Map.of("id", sale))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM catalog_sale_role_snapshots WHERE item_id = :id",
                Map.of("id", sale))).isInstanceOf(DataAccessException.class);
    }

    @Test
    void linkedReturnInheritsOriginalDespiteRevocationAndRenamedProduct() {
        var fixture = fixture();
        UUID sale = item(fixture, null, fixture.confirmedAt());
        capture(sale);
        compatibility.append(compatibility.observe(fixture.product(), false), 2,
                new Request(Action.REVOKE, Coverage.UNDETERMINED, List.of(), "Synthetic revoke",
                        Origin.DIRECT_REVIEW, null, null), fixture.actor());
        jdbc.update("UPDATE products SET name = 'Changed after sale' WHERE id = :id",
                Map.of("id", fixture.product()));
        UUID returned = item(fixture, sale, databaseNow());
        String before = factRow(returned);
        capture(returned);
        assertThat(snapshot(returned)).containsEntry("role", "ACCESSORY_APPLE_WATCH")
                .containsEntry("origin", "ORIGINAL_SALE")
                .containsEntry("original_snapshot_item_id", sale).containsEntry("state", "CURRENT");
        assertThat(snapshot(returned).get("confirmation_id")).isEqualTo(snapshot(sale).get("confirmation_id"));
        assertThat(factRow(returned)).isEqualTo(before);
    }

    @Test
    void sourceClassificationChangeInvalidatesSaleAndInheritedReturnButPriceChangeDoesNot() {
        var fixture = fixture();
        UUID sale = item(fixture, null, fixture.confirmedAt());
        capture(sale);
        UUID returned = item(fixture, sale, databaseNow());
        capture(returned);
        jdbc.update("UPDATE sales_document_items SET unit_price = 150 WHERE id = :id", Map.of("id", sale));
        assertThat(snapshot(sale)).containsEntry("state", "CURRENT");
        jdbc.update("UPDATE sales_document_items SET product_name_snapshot = 'Corrected fact' WHERE id = :id",
                Map.of("id", sale));
        assertThat(snapshot(sale)).containsEntry("state", "STALE");
        assertThat(snapshot(returned)).containsEntry("state", "STALE");
        capture(sale);
        assertThat(snapshot(sale)).containsEntry("state", "STALE");
        UUID secondReturn = item(fixture, sale, databaseNow());
        capture(secondReturn);
        assertThat(snapshot(secondReturn)).containsEntry("origin", "LEGACY_RETURN")
                .containsEntry("outcome", "DEFER_TO_EXISTING").containsEntry("role", null);
    }

    @Test
    void returnWithoutSnapshotNeverUsesCurrentProductConfirmationAndDoesNotAutoUpgrade() {
        var fixture = fixture();
        UUID sale = item(fixture, null, fixture.confirmedAt());
        UUID returned = item(fixture, sale, databaseNow());
        capture(returned);
        var before = snapshot(returned);
        assertThat(before).containsEntry("origin", "LEGACY_RETURN").containsEntry("confirmation_id", null);
        capture(sale);
        capture(returned);
        assertThat(snapshot(returned)).isEqualTo(before);
    }

    @Test
    void prospectiveBoundarySkipsOldFactsAndDoesNotBorrowFutureConfirmation() {
        var fixture = fixture();
        UUID oldSale = item(fixture, null, fixture.confirmedAt().minusSeconds(2));
        var writer = writer(true, fixture.confirmedAt().toString());
        transactions.executeWithoutResult(status -> writer.captureNewItem(oldSale));
        assertThat(jdbc.queryForList("SELECT item_id FROM catalog_sale_role_snapshots WHERE item_id = :id",
                Map.of("id", oldSale))).isEmpty();
        capture(oldSale);
        assertThat(snapshot(oldSale)).containsEntry("confirmation_id", null)
                .containsEntry("outcome", "DEFER_TO_EXISTING").containsEntry("role", null);
    }

    @Test
    void disabledWriterDoesNotFlushOrTouchDatabaseAndEnabledWriterRequiresCutoff() {
        var localJdbc = mock(NamedParameterJdbcTemplate.class);
        var entityManager = mock(EntityManager.class);
        var reader = mock(CatalogCompatibilityProjectionService.class);
        var repository = mock(CatalogCompatibilityRepository.class);
        new CatalogSaleRoleSnapshotWriter(localJdbc, entityManager, repository, reader,
                Clock.systemUTC(), false, "").captureNewItem(UUID.randomUUID());
        verifyNoInteractions(localJdbc, entityManager, reader, repository);
        assertThatThrownBy(() -> new CatalogSaleRoleSnapshotWriter(localJdbc, entityManager, repository, reader,
                Clock.systemUTC(), true, "")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shadowInsertRollsBackWithItsEnclosingTransaction() {
        var fixture = fixture();
        UUID sale = item(fixture, null, fixture.confirmedAt());
        transactions.executeWithoutResult(status -> {
            writer(true, Instant.EPOCH.toString()).captureNewItem(sale);
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForList("SELECT item_id FROM catalog_sale_role_snapshots WHERE item_id = :id",
                Map.of("id", sale))).isEmpty();
    }


    @Test
    void officialRatesUseWatchRoleOnceAndReturnKeepsItsOriginalRoleAfterRevocation() {
        var f = fixture();
        UUID sale = item(f, null, f.confirmedAt());
        String before = factRow(sale);
        capture(sale);
        assertThat(snapshot(sale)).containsEntry("role", "ACCESSORY_APPLE_WATCH")
                .containsEntry("outcome", "ASSIGNED").containsEntry("state", "CURRENT");
        compatibility.append(compatibility.observe(f.product(), false), 2,
                new Request(Action.REVOKE, Coverage.UNDETERMINED, List.of(), "Synthetic revoke",
                        Origin.DIRECT_REVIEW, null, null), f.actor());
        UUID returned = item(f, sale, databaseNow());
        jdbc.update("UPDATE sales_document_items SET quantity = 0.5 WHERE id = :id", Map.of("id", returned));
        capture(returned);
        assertThat(snapshot(returned)).containsEntry("role", "ACCESSORY_APPLE_WATCH")
                .containsEntry("origin", "ORIGINAL_SALE").containsEntry("state", "CURRENT");
        for (boolean v4 : List.of(false, true)) {
            assertNumerator(f, v4, "CHARGER_CABLE", "0");
            assertNumerator(f, v4, "ACCESSORY_APPLE_WATCH", "0.5");
            assertNumerator(f, v4, "ACCESSORY_PODS_WATCH", "0.5");
        }
        assertThat(factRow(sale)).isEqualTo(before);
        assertThat(review(f, sale)).isNull();
        assertThat(new com.storeanalytics.metrics.cases.CaseAttachRepository(jdbc)
                .unresolvedReturnCount(f.store(), java.time.LocalDate.of(2000, 1, 1),
                        java.time.LocalDate.of(2100, 1, 1))).isZero();
    }

    @Test
    void changedSourceRequiresReviewAndManualDecisionRecalculatesSaleAndPartialReturn() {
        var f = fixture();
        UUID sale = item(f, null, f.confirmedAt());
        capture(sale);
        UUID returned = item(f, sale, databaseNow());
        capture(returned);
        jdbc.update("UPDATE sales_document_items SET quantity = 0.25 WHERE id = :id", Map.of("id", returned));
        jdbc.update("UPDATE sales_document_items SET source_group_name_snapshot = 'Changed' WHERE id = :id",
                Map.of("id", sale));
        assertThat(review(f, sale).allowedTargets()).contains("ACCESSORY_APPLE_WATCH", "CHARGER_CABLE", "NO_ATTACH");
        assertNumerator(f, true, "ACCESSORY_APPLE_WATCH", "0");
        assertThat(rate(f, true, "CHARGER_CABLE").preliminary()).isTrue();
        decide(f, sale, "ACCESSORY_APPLE_WATCH");
        assertNumerator(f, true, "ACCESSORY_APPLE_WATCH", "0.75");
        assertNumerator(f, true, "ACCESSORY_PODS_WATCH", "0.75");
        assertNumerator(f, true, "CHARGER_CABLE", "0");
        assertThat(rate(f, true, "ACCESSORY_APPLE_WATCH").preliminary()).isFalse();
        assertThat(snapshot(sale)).containsEntry("state", "STALE");
        decide(f, sale, "NO_ATTACH");
        assertNumerator(f, true, "ACCESSORY_APPLE_WATCH", "0");
        assertNumerator(f, true, "ACCESSORY_PODS_WATCH", "0");
        assertThat(rate(f, true, "CHARGER_CABLE").preliminary()).isFalse();
        decide(f, sale, "DEFER");
        assertThat(rate(f, true, "CHARGER_CABLE").preliminary()).isTrue();
    }

    @Test
    void legacyReturnIsNotSilentlyUpgradedButCanBeReconciledByExplicitSaleDecision() {
        var f = fixture();
        UUID sale = item(f, null, f.confirmedAt());
        UUID returned = item(f, sale, databaseNow());
        capture(returned);
        var originalSnapshot = snapshot(returned);
        capture(sale);
        assertThat(review(f, sale)).isNotNull();
        assertNumerator(f, true, "ACCESSORY_APPLE_WATCH", "1");
        assertNumerator(f, true, "CHARGER_CABLE", "-1");
        assertThat(rate(f, true, "CHARGER_CABLE").preliminary()).isTrue();
        decide(f, sale, "ACCESSORY_APPLE_WATCH");
        assertNumerator(f, true, "ACCESSORY_APPLE_WATCH", "0");
        assertNumerator(f, true, "CHARGER_CABLE", "0");
        assertThat(snapshot(returned)).isEqualTo(originalSnapshot);
        jdbc.update("UPDATE sales_document_items SET is_deleted = true WHERE id = :id", Map.of("id", returned));
        assertThat(review(f, sale).decisionCurrent()).isTrue();
        assertNumerator(f, true, "ACCESSORY_APPLE_WATCH", "1");
    }

    @Test
    void confirmedPhoneFilmAppliesAutomaticallyWhileTabletFilmHasNoContribution() {
        for (Target target : List.of(Target.IPHONE, Target.IPAD)) {
            var f = fixture(target, "Synthetic protective film", "PROTECTIVE_FILM");
            UUID sale = item(f, null, f.confirmedAt());
            capture(sale);
            assertThat(snapshot(sale)).containsEntry("state", "CURRENT")
                    .containsEntry("outcome", target == Target.IPHONE ? "ASSIGNED" : "NO_CONTRIBUTION");
            UUID returned = item(f, sale, databaseNow());
            jdbc.update("UPDATE sales_document_items SET quantity = 0.25 WHERE id = :id", Map.of("id", returned));
            capture(returned);
            assertThat(snapshot(returned)).containsEntry("state", "CURRENT").containsEntry("origin", "ORIGINAL_SALE");
            assertNumerator(f, true, "FILM_PHONE", target == Target.IPHONE ? "0.75" : "0");
            assertThat(review(f, sale)).withFailMessage("Unexpected film review: sale=%s return=%s",
                    snapshot(sale), snapshot(returned)).isNull();
            assertThat(rate(f, true, "FILM_PHONE").preliminary()).isFalse();
        }
    }

    @Test
    void pendingRoleReturnMarksItsOwnPeriodEvenWhenOriginalSaleIsOutsideIt() {
        var f = fixture();
        UUID sale = item(f, null, f.confirmedAt());
        capture(sale);
        UUID returned = item(f, sale, databaseNow());
        capture(returned);
        jdbc.update("UPDATE sales_document_items SET product_name_snapshot = 'Correction' WHERE id = :id",
                Map.of("id", sale));
        jdbc.update("UPDATE sales_documents SET business_date = '2020-01-01' WHERE id = "
                + "(SELECT sales_document_id FROM sales_document_items WHERE id = :id)", Map.of("id", sale));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_pending_role_returns "
                + "WHERE store_id = :store AND business_date > '2020-01-01'",
                Map.of("store", f.store()), Long.class))
                .withFailMessage("Pending return: %s", jdbc.queryForMap("""
                    SELECT d.business_date, s.state,
                        catalog_exact_return_original(i.id) AS original,
                        catalog_review_replaces_automatic(i.original_item_id) AS replaced,
                        catalog_role_pending_issue(i.id) AS issue
                    FROM sales_document_items i JOIN sales_documents d ON d.id = i.sales_document_id
                    JOIN catalog_sale_role_snapshot_states s ON s.item_id = i.id WHERE i.id = :id
                    """, Map.of("id", returned))).isEqualTo(1);
        assertNumerator(f, true, "CHARGER_CABLE", "0");
        assertThat(rate(f, true, "ACCESSORY_APPLE_WATCH").preliminary()).isTrue();
        var qualities = transactions.execute(status ->
                new com.storeanalytics.metrics.repository.AttachAttributionQualityRepository(jdbc)
                .read(f.store(), java.time.LocalDate.of(2021, 1, 1), java.time.LocalDate.of(2100, 1, 1)));
        assertThat(qualities.stream().filter(q -> q.metricCode().equals("ACCESSORY_APPLE_WATCH"))
                .findFirst().orElseThrow().preliminary()).isTrue();
        assertThat(qualities.stream().filter(q -> q.metricCode().equals("POWER_BANK"))
                .findFirst().orElseThrow().preliminary()).isFalse();
    }

    @Test
    void boundedPendingRoleLookupPreservesRolesReviewsAndReturns() throws IOException {
        String previous = migration("V86__apply_confirmed_catalog_attach_roles.sql");
        int start = previous.indexOf("CREATE FUNCTION catalog_role_pending_issue(");
        int end = previous.indexOf("CREATE FUNCTION catalog_attach_metric_uncertain(", start);
        assertThat(start).isGreaterThanOrEqualTo(0);
        assertThat(end).isGreaterThan(start);
        String legacy = previous.substring(start, end).replace("CREATE FUNCTION", "CREATE OR REPLACE FUNCTION");
        String optimized = migration("V88__bound_catalog_pending_role_lookup.sql");

        var f = fixture();
        UUID sale = item(f, null, f.confirmedAt());
        capture(sale);
        UUID returned = item(f, sale, databaseNow());
        jdbc.update("UPDATE sales_document_items SET quantity = 0.25 WHERE id = :id", Map.of("id", returned));
        capture(returned);
        UUID orphan = item(f, null, databaseNow());
        jdbc.update("UPDATE sales_documents SET document_kind = 'RETURN' WHERE id = "
                + "(SELECT sales_document_id FROM sales_document_items WHERE id = :id)", Map.of("id", orphan));
        var film = fixture(Target.IPHONE, "Synthetic unresolved film", "PROTECTIVE_FILM");
        item(film, null, film.confirmedAt().minusSeconds(2));
        assertPendingRoleEquivalence(f, legacy, optimized);
        assertPendingRoleEquivalence(film, legacy, optimized);

        jdbc.update("UPDATE sales_document_items SET product_name_snapshot = 'Corrected role fact' "
                + "WHERE id = :id", Map.of("id", sale));
        assertPendingRoleEquivalence(f, legacy, optimized);
        decide(f, sale, "ACCESSORY_APPLE_WATCH");
        assertPendingRoleEquivalence(f, legacy, optimized);
        decide(f, sale, "NO_ATTACH");
        assertPendingRoleEquivalence(f, legacy, optimized);
        decide(f, sale, "DEFER");
        assertPendingRoleEquivalence(f, legacy, optimized);
        jdbc.update("UPDATE sales_documents SET business_date = '2020-01-01' WHERE id = "
                + "(SELECT sales_document_id FROM sales_document_items WHERE id = :id)", Map.of("id", sale));
        assertPendingRoleEquivalence(f, legacy, optimized);
        jdbc.update("UPDATE sales_document_items SET is_deleted = true WHERE id = :id", Map.of("id", returned));
        assertPendingRoleEquivalence(f, legacy, optimized);
    }

    private String migration(String name) throws IOException {
        try (var stream = getClass().getResourceAsStream("/db/migration/" + name)) {
            if (stream == null) {
                throw new IOException("Missing migration " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void assertPendingRoleEquivalence(Fixture f, String legacy, String optimized) {
        transactions.executeWithoutResult(status -> {
            jdbc.getJdbcTemplate().execute("SET LOCAL jit = off");
            jdbc.getJdbcTemplate().execute(legacy);
            var before = pendingRoleProjections(f);
            jdbc.getJdbcTemplate().execute(optimized);
            assertThat(pendingRoleProjections(f)).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT catalog_role_pending_issue(NULL::uuid)",
                    Map.of(), String.class)).isNull();
            status.setRollbackOnly();
        });
    }

    private Map<String, List<String>> pendingRoleProjections(Fixture f) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String view : List.of("case_attach_review_items", "catalog_pending_role_returns",
                "attach_rate_item_facts_v3_catalog", "attach_rate_item_facts_v4_catalog")) {
            result.put(view, jdbc.queryForList("SELECT to_jsonb(f)::text FROM " + view
                    + " f WHERE f.store_id = :store ORDER BY to_jsonb(f)::text",
                    Map.of("store", f.store()), String.class));
        }
        result.put("roles", jdbc.queryForList("""
                SELECT jsonb_build_array(i.id, catalog_role_pending_issue(i.id))::text
                FROM sales_document_items i JOIN sales_documents d ON d.id = i.sales_document_id
                WHERE d.store_id = :store ORDER BY i.id
                """, Map.of("store", f.store()), String.class));
        return result;
    }

    private com.storeanalytics.metrics.cases.CaseAttachViews.Case review(
            Fixture f, UUID sale
    ) {
        return new com.storeanalytics.metrics.cases.CaseAttachRepository(jdbc).find(f.store(), sale);
    }

    private void decide(Fixture f, UUID sale, String target) {
        transactions.executeWithoutResult(status -> {
            jdbc.getJdbcTemplate().execute("SET LOCAL jit = off");
            var repository = new com.storeanalytics.metrics.cases.CaseAttachRepository(jdbc);
            repository.save(repository.find(f.store(), sale),
                    new com.storeanalytics.metrics.cases.CaseAttachDecisionRequest(
                            target, "Synthetic reviewed evidence"),
                    f.actor(), f.store());
        });
    }

    private com.storeanalytics.metrics.repository.AttachRateAggregate rate(Fixture f, boolean v4, String metric) {
        return transactions.execute(status -> {
            jdbc.getJdbcTemplate().execute("SET LOCAL jit = off");
            return new com.storeanalytics.metrics.repository.AttachRateRepository(jdbc,
                    new com.storeanalytics.metrics.warranty.AttachAttributionPolicy(v4))
                    .aggregate(f.store(), java.time.LocalDate.of(2000, 1, 1),
                            java.time.LocalDate.of(2100, 1, 1)).stream()
                    .filter(value -> value.metricCode().equals(metric)).findFirst().orElseThrow();
        });
    }

    private void assertNumerator(Fixture f, boolean v4, String metric, String expected) {
        assertThat(rate(f, v4, metric).numeratorReceiptCount()).isEqualByComparingTo(expected);
    }

    private Instant databaseNow() {
        // The Docker VM and JVM can be ahead of one another in either direction.
        // Both confirmation timestamps and database document timestamps must be in the past
        // of the synthetic capture clock, including return events after their original sale.
        Instant database = jdbc.queryForObject("SELECT clock_timestamp()", Map.of(),
                (row, index) -> row.getTimestamp(1).toInstant());
        Instant process = Instant.now();
        return database.isAfter(process) ? database : process;
    }

    private void capture(UUID itemId) {
        transactions.executeWithoutResult(status -> writer(true, Instant.EPOCH.toString()).captureNewItem(itemId));
    }

    private CatalogSaleRoleSnapshotWriter writer(boolean enabled, String from) {
        return new CatalogSaleRoleSnapshotWriter(jdbc, mock(EntityManager.class), compatibility,
                new CatalogCompatibilityProjectionService(compatibility),
                Clock.fixed(databaseNow(), java.time.ZoneOffset.UTC), enabled, from);
    }

    private Map<String, Object> snapshot(UUID item) {
        return jdbc.queryForMap("SELECT * FROM catalog_sale_role_snapshot_states WHERE item_id = :id",
                Map.of("id", item));
    }

    private String factRow(UUID item) {
        return jdbc.queryForObject("SELECT to_jsonb(i)::text FROM sales_document_items i WHERE id = :id",
                Map.of("id", item), String.class);
    }

    private Fixture fixture() {
        return fixture(Target.APPLE_WATCH, "Synthetic watch charger", "CHARGER_CABLE");
    }

    private Fixture fixture(Target target, String name, String category) {
        UUID actor = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        UUID store = UUID.randomUUID();
        UUID sync = UUID.randomUUID();
        UUID connection = jdbc.queryForObject("SELECT id FROM integration_connections "
                + "WHERE connection_key = 'livesklad-default'", Map.of(), UUID.class);
        jdbc.update("""
                INSERT INTO app_users(id,email,password_hash,display_name,role,password_change_required)
                VALUES (:id,:email,'synthetic-not-a-credential','Synthetic actor','ADMIN',false)
                """, Map.of("id", actor, "email", actor + "@example.invalid"));
        jdbc.update("INSERT INTO stores(id,connection_id,name) VALUES (:id,:connection,'Synthetic store')",
                Map.of("id", store, "connection", connection));
        jdbc.update("""
                INSERT INTO sync_runs(id,connection_id,source_system,trigger_type,sync_scope,status)
                VALUES (:id,:connection,'LIVESKLAD','MANUAL','SALES','RUNNING')
                """, Map.of("id", sync, "connection", connection));
        jdbc.update("""
                INSERT INTO products(id,connection_id,external_id,code,name,source_kind)
                VALUES (:id,:connection,:external,:code,:name,'PRODUCT')
                """, Map.of("id", product, "connection", connection,
                        "external", product.toString(), "code", "test", "name", name));
        var confirmed = compatibility.append(compatibility.observe(product, false), 1,
                new Request(Action.CONFIRM, Coverage.EXCLUSIVE, List.of(target),
                        "Synthetic confirmation",
                        Origin.DIRECT_REVIEW, null, null), actor);
        return new Fixture(connection, store, sync, product, actor, confirmed.recordedAt(), name, category);
    }

    private UUID item(Fixture fixture, UUID original, Instant occurred) {
        UUID document = UUID.randomUUID();
        UUID item = UUID.randomUUID();
        UUID originalDocument = original == null ? null : jdbc.queryForObject(
                "SELECT sales_document_id FROM sales_document_items WHERE id = :id",
                Map.of("id", original), UUID.class);
        var parameters = new MapSqlParameterSource("id", document).addValue("external", document.toString())
                .addValue("connection", fixture.connection()).addValue("store", fixture.store())
                .addValue("sync", fixture.sync()).addValue("originalDocument", originalDocument)
                .addValue("kind", original == null ? "SALE" : "RETURN").addValue("at", Timestamp.from(occurred))
                .addValue("name", fixture.name()).addValue("category", fixture.category())
                .addValue("item", item).addValue("original", original).addValue("product", fixture.product());
        jdbc.update("""
                INSERT INTO sales_documents(id,connection_id,external_id,store_id,original_document_id,
                    document_kind,source_document_type,occurred_at,business_date,net_amount,last_sync_run_id)
                VALUES (:id,:connection,:external,:store,:originalDocument,:kind,'synthetic',:at,
                    CAST(:at AS timestamptz)::date,100,:sync)
                """, parameters);
        jdbc.update("""
                INSERT INTO sales_document_items(id,sales_document_id,external_id,original_item_id,product_id,
                    product_name_snapshot,analytics_category_id,condition_type_snapshot,quantity,unit_price,
                    gross_amount,discount_amount,net_amount,cost_amount,cost_quality)
                SELECT :item,:id,:external,:original,:product,:name,id,'NOT_APPLICABLE',
                    1,100,100,0,100,50,'KNOWN' FROM analytics_categories WHERE code = :category
                """, parameters);
        return item;
    }

    private record Fixture(
            UUID connection, UUID store, UUID sync, UUID product, UUID actor,
            Instant confirmedAt, String name, String category
    ) { }
}
