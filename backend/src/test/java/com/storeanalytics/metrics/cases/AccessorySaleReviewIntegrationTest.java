package com.storeanalytics.metrics.cases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class AccessorySaleReviewIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        for (String code : new String[]{"CASE_UNIVERSAL", "GLASS_PHONE_UNRESOLVED", "PROTECTIVE_FILM"}) {
            jdbc.update("INSERT INTO analytics_categories(code,name,category_kind,device_family,"
                    + "counts_as_additional_revenue) VALUES (?,?,'ACCESSORY','NONE',true) ON CONFLICT DO NOTHING",
                    code, "Synthetic " + code);
        }
    }

    static Stream<Arguments> decisions() {
        return Stream.of(
                Arguments.of("OTHER_CASE", "CASE_APPLE_IPHONE", "CASE_APPLE_IPHONE"),
                Arguments.of("CASE_UNIVERSAL", "CASE_SAMSUNG", "CASE_SAMSUNG"),
                Arguments.of("CASE_UNIVERSAL", "CASE_OTHER_DEVICE", null),
                Arguments.of("GLASS_PHONE_UNRESOLVED", "GLASS_IPHONE", "GLASS_IPHONE"),
                Arguments.of("GLASS_PHONE_UNRESOLVED", "GLASS_SAMSUNG", "GLASS_SAMSUNG"),
                Arguments.of("GLASS_PHONE_UNRESOLVED", "GLASS_OTHER", null),
                Arguments.of("PROTECTIVE_FILM", "FILM_PHONE", "FILM_PHONE"),
                Arguments.of("PROTECTIVE_FILM", "FILM_NON_PHONE", null));
    }

    @ParameterizedTest
    @MethodSource("decisions")
    void confirmedSaleAndReturnContributeOnceAndPreserveMoney(String category, String target, String metric) {
        UUID source = fixture(category);
        String before = fact(source);
        var repository = new CaseAttachRepository(
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc));
        UUID storeId = jdbc.queryForObject("SELECT store_id FROM case_attach_review_items WHERE source_item_id=?",
                UUID.class, source);
        assertThat(repository.find(storeId, source).allowedTargets()).contains(target, "DEFER");
        assertThat(quantity(source, "attach_rate_item_facts_v4_with_cases", target)).isZero();
        decide(source, target, 1);
        assertThat(fact(source)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attach_attribution_changes WHERE store_id = "
                + "(SELECT store_id FROM case_attach_review_items WHERE source_item_id = ?)",
                Integer.class, source)).isEqualTo(1);
        for (String view : new String[]{
                "attach_rate_item_facts_v3_with_cases", "attach_rate_item_facts_v4_with_cases"}) {
            assertThat(quantity(source, view, target)).isEqualByComparingTo(metric == null ? "0" : "1.250");
        }
        assertThatThrownBy(() -> decide(source, target, 1)).isInstanceOf(DataAccessException.class);
        decide(source, "DEFER", 2);
        assertThat(quantity(source, "attach_rate_item_facts_v4_with_cases", target)).isZero();
        assertThat(fact(source)).isEqualTo(before);
    }

    @Test
    void unknownReturnAuthorLimitsOnlyTheAffectedMetricInBothStoreAndSellerQuality() {
        UUID source = fixture("GLASS_PHONE_UNRESOLVED");
        decide(source, "GLASS_SAMSUNG", 1);
        UUID store = jdbc.queryForObject("SELECT store_id FROM case_attach_review_items WHERE source_item_id=?",
                UUID.class, source);
        var named = new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc);
        var transaction = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        transaction.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL jit = off");
            var quality = new com.storeanalytics.metrics.repository.AttachAttributionQualityRepository(named)
                    .read(store, java.time.LocalDate.parse("2026-09-01"), java.time.LocalDate.parse("2026-09-02"));
            assertThat(quality.stream().filter(q -> q.metricCode().equals("GLASS_SAMSUNG")).findFirst().orElseThrow()
                    .unassignedMetricReturnItemCount()).isEqualTo(1);
            assertThat(quality.stream().filter(q -> q.metricCode().equals("CASE_SAMSUNG")).findFirst().orElseThrow()
                    .unassignedMetricReturnItemCount()).isZero();
            var aggregate = new com.storeanalytics.metrics.repository.AttachRateRepository(named,
                    new com.storeanalytics.metrics.warranty.AttachAttributionPolicy(true))
                    .aggregate(store, java.time.LocalDate.parse("2026-09-01"), java.time.LocalDate.parse("2026-09-02"));
            assertThat(aggregate.stream().filter(q -> q.metricCode().equals("GLASS_SAMSUNG"))
                    .findFirst().orElseThrow().unassignedMetricReturnItemCount()).isEqualTo(1);
        });
    }

    @Test
    void glassCannotBeConfirmedAsCaseAndSourceChangesInvalidateDecision() {
        UUID source = fixture("GLASS_PHONE_UNRESOLVED");
        assertThatThrownBy(() -> decide(source, "CASE_APPLE_IPHONE", 1)).isInstanceOf(DataAccessException.class);
        decide(source, "GLASS_IPHONE", 1);
        jdbc.update("UPDATE sales_document_items SET unit_price = 120 WHERE id = ?", source);
        assertThat(quantity(source, "attach_rate_item_facts_v4_with_cases", "GLASS_IPHONE"))
                .isEqualByComparingTo("1.250");
        jdbc.update("UPDATE sales_document_items SET product_name_snapshot = 'Changed observation' WHERE id = ?",
                source);
        assertThat(quantity(source, "attach_rate_item_facts_v4_with_cases", "GLASS_IPHONE")).isZero();
        assertThat(jdbc.queryForObject("SELECT decision_current FROM case_attach_review_items "
                + "WHERE source_item_id = ?", Boolean.class, source)).isFalse();
    }

    @Test
    void linkedReturnNeverDuplicatesAutomaticClassificationAndExcludedReturnDoesNotContribute() {
        UUID source = fixture("GLASS_PHONE_UNRESOLVED");
        decide(source, "GLASS_IPHONE", 1);
        jdbc.update("UPDATE sales_document_items SET analytics_category_id = "
                + "(SELECT id FROM analytics_categories WHERE code='GLASS_IPHONE') WHERE original_item_id=?", source);
        for (String view : new String[]{
                "attach_rate_item_facts_v3_with_cases", "attach_rate_item_facts_v4_with_cases"}) {
            assertThat(quantity(source, view, "GLASS_IPHONE")).isEqualByComparingTo("1.250");
        }
        decide(source, "DEFER", 2);
        assertThat(quantity(source, "attach_rate_item_facts_v4_with_cases", "GLASS_IPHONE")).isZero();
        decide(source, "GLASS_IPHONE", 3);
        jdbc.update("UPDATE sales_document_items SET analytics_category_id = "
                + "(SELECT id FROM analytics_categories WHERE code='EXCLUDE') WHERE original_item_id=?", source);
        assertThat(quantity(source, "attach_rate_item_facts_v4_with_cases", "GLASS_IPHONE"))
                .isEqualByComparingTo("2.000");
    }

    @Test
    void mismatchedReturnDoesNotReducePreviewAndIsReportedForReview() {
        UUID source = fixture("OTHER_CASE");
        UUID other = fixture("OTHER_CASE");
        UUID wrongProduct = jdbc.queryForObject(
                "SELECT product_id FROM sales_document_items WHERE id=?", UUID.class, other);
        UUID store = jdbc.queryForObject(
                "SELECT store_id FROM case_attach_review_items WHERE source_item_id=?",
                UUID.class, source);
        var repository = new CaseAttachRepository(
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc));
        var start = java.time.LocalDate.parse("2026-09-01");
        var end = java.time.LocalDate.parse("2026-09-02");

        assertThat(repository.netQuantity(store, source)).isEqualByComparingTo("1.250");
        assertThat(repository.unresolvedReturnCount(store, start, end)).isZero();
        jdbc.update("UPDATE sales_document_items SET product_id=? WHERE original_item_id=?",
                wrongProduct, source);
        assertThat(repository.netQuantity(store, source)).isEqualByComparingTo("2.000");
        assertThat(repository.affectedDates(store, source)).containsExactly(start);
        assertThat(repository.unresolvedReturnCount(store, start, end)).isEqualTo(1);
        decide(source, "CASE_APPLE_IPHONE", 1);
        assertThat(quantity(source, "attach_rate_item_facts_v4_with_cases", "CASE_APPLE_IPHONE"))
                .isEqualByComparingTo("2.000");
    }

    @Test
    void knownOtherGlassAndBrandedCasesNeverEnterReview() {
        UUID source = fixture("GLASS_IPHONE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM case_attach_review_items WHERE source_item_id = ?",
                Integer.class, source)).isZero();
        assertThatThrownBy(() -> decide(source, "GLASS_SAMSUNG", 1)).isInstanceOf(DataAccessException.class);
    }

    private java.math.BigDecimal quantity(UUID source, String view, String metric) {
        return jdbc.queryForObject("SELECT COALESCE(sum(net_quantity),0) FROM " + view
                + " WHERE numerator_metric_code = ? AND store_id = (SELECT d.store_id "
                + "FROM sales_document_items i JOIN sales_documents d ON d.id=i.sales_document_id WHERE i.id=?)",
                java.math.BigDecimal.class, metric, source);
    }

    private void decide(UUID source, String target, int revision) {
        var row = jdbc.queryForMap("SELECT d.store_id, COALESCE(r.source_fingerprint,'absent') AS fingerprint "
                + "FROM sales_document_items i JOIN sales_documents d ON d.id=i.sales_document_id "
                + "LEFT JOIN case_attach_review_items r ON r.source_item_id=i.id WHERE i.id=?", source);
        jdbc.update("INSERT INTO case_attach_decisions(source_item_id,store_id,target_code,source_fingerprint,"
                + "reason,revision) VALUES (?,?,?,?, 'Synthetic checked marking',?)",
                source, row.get("store_id"), target, row.get("fingerprint"), revision);
    }

    private String fact(UUID source) {
        return jdbc.queryForObject("SELECT to_jsonb(i)::text FROM sales_document_items i WHERE id=?",
                String.class, source);
    }

    private UUID fixture(String category) {
        UUID connection = jdbc.queryForObject("SELECT id FROM integration_connections "
                + "WHERE connection_key='livesklad-default'", UUID.class);
        UUID store = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        UUID sale = UUID.randomUUID();
        UUID returned = UUID.randomUUID();
        UUID item = UUID.randomUUID();
        jdbc.update("INSERT INTO stores(id,connection_id,name) VALUES (?,?,'Synthetic review store')",
                store, connection);
        jdbc.update("INSERT INTO sync_runs(id,connection_id,source_system,trigger_type,sync_scope,status) "
                + "VALUES (?,?,'LIVESKLAD','MANUAL','SALES','RUNNING')", run, connection);
        jdbc.update("INSERT INTO products(id,connection_id,external_id,name,source_kind) "
                + "VALUES (?,?,?,'Synthetic accessory','PRODUCT')", product, connection, product.toString());
        document(sale, connection, store, run, null, "SALE", "2026-09-01");
        document(returned, connection, store, run, sale, "RETURN", "2026-09-02");
        insertItem(item, sale, null, product, category, "2.000");
        insertItem(UUID.randomUUID(), returned, item, product, category, "0.750");
        return item;
    }

    private void document(UUID id, UUID connection, UUID store, UUID run, UUID original, String kind, String date) {
        jdbc.update("INSERT INTO sales_documents(id,connection_id,external_id,store_id,original_document_id,"
                + "document_kind,source_document_type,occurred_at,business_date,net_amount,last_sync_run_id) "
                + "VALUES (?,?,?,?,?,?,'synthetic',?::timestamptz,?::date,100,?)",
                id, connection, id.toString(), store, original, kind, date, date, run);
    }

    private void insertItem(UUID id, UUID document, UUID original, UUID product, String category, String quantity) {
        jdbc.update("INSERT INTO sales_document_items(id,sales_document_id,external_id,original_item_id,product_id,"
                + "product_name_snapshot,analytics_category_id,condition_type_snapshot,quantity,unit_price,"
                + "gross_amount,discount_amount,net_amount,cost_amount,cost_quality) "
                + "SELECT ?,?,?,?,?,'Synthetic accessory',id,'NOT_APPLICABLE',?::numeric,100,100,0,100,50,'KNOWN' "
                + "FROM analytics_categories WHERE code=?", id, document, id.toString(), original, product,
                quantity, category);
    }
}
