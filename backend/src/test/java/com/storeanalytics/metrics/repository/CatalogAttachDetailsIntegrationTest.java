package com.storeanalytics.metrics.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.metrics.service.AttachRateService;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import com.storeanalytics.metrics.warranty.AttachAttributionPolicy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class CatalogAttachDetailsIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    static JdbcTemplate jdbc;
    static TransactionTemplate transaction;
    static final LocalDate START = LocalDate.parse("2026-09-01");
    static final LocalDate END = LocalDate.parse("2026-09-30");

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    @Test
    void childrenAndSummaryUseRawQuantitiesAndOneReturnIsOneQualityIssue() {
        var graph = graph();
        item(graph, "HEADPHONES_APPLE", "AirPods Pro", "2.000", null);
        item(graph, "WATCH_APPLE", "Apple Watch", "5.000", null);
        var accessory = item(graph, "ACCESSORY_AIRPODS", "Чехол", "3.000", null);
        item(graph, "ACCESSORY_AIRPODS", "Чехол", "0.500", accessory);
        item(graph, "ACCESSORY_APPLE_WATCH", "Ремешок", "1.000", null);
        var rates = read(graph, true);
        assertQuantities(rates, "ACCESSORY_AIRPODS", "2.500", "2.000");
        assertQuantities(rates, "ACCESSORY_APPLE_WATCH", "1.000", "5.000");
        assertQuantities(rates, "ACCESSORY_PODS_WATCH", "3.500", "7.000");
        var result = AttachRateService.project(graph.store(), new StoreKpiPeriod(START, END), "attach-rate-v4", rates);
        assertThat(result.rates().stream().filter(rate -> rate.metricCode().equals("ACCESSORY_PODS_WATCH"))
                .findFirst().orElseThrow().ratePerHundred()).isEqualByComparingTo("50.00");
        assertThat(find(rates, "ACCESSORY_AIRPODS").unassignedReturnItemCount()).isOne();
        assertThat(find(rates, "ACCESSORY_AIRPODS").unassignedMetricReturnItemCount()).isOne();
        assertThat(find(rates, "ACCESSORY_PODS_WATCH").unassignedMetricReturnItemCount()).isOne();
        assertThat(find(rates, "ACCESSORY_APPLE_WATCH").unassignedMetricReturnItemCount()).isZero();
    }

    @Test
    void legacyUnknownRemainsInSummaryAndConflictingNamesAreNotGuessed() {
        var graph = graph();
        item(graph, "HEADPHONES_APPLE", "AirPods Pro", "2.000", null);
        item(graph, "ACCESSORY_PODS_WATCH", "Чехол AirPods", "1.000", null);
        item(graph, "ACCESSORY_PODS_WATCH", "Ремешок Apple Watch", "2.000", null);
        item(graph, "ACCESSORY_PODS_WATCH", "Аксессуар AirPods Apple Watch", "0.250", null);
        item(graph, "ACCESSORY_PODS_WATCH", "Чехол без назначения", "1.000", null);
        item(graph, "ACCESSORY_PODS_WATCH", "Чехол Samsung Buds", "9.000", null);
        for (boolean v4 : new boolean[]{false, true}) {
            var rates = read(graph, v4);
            assertQuantities(rates, "ACCESSORY_AIRPODS", "1.000", "2.000");
            assertQuantities(rates, "ACCESSORY_APPLE_WATCH", "2.000", "0.000");
            assertQuantities(rates, "ACCESSORY_PODS_WATCH", "4.250", "2.000");
            assertThat(find(rates, "ACCESSORY_AIRPODS").preliminary()).isTrue();
            assertThat(find(rates, "ACCESSORY_APPLE_WATCH").preliminary()).isTrue();
            assertThat(find(rates, "ACCESSORY_PODS_WATCH").preliminary()).isFalse();
        }
    }

    @Test
    void earPodsNeverBecomeAirPodsBaseAndLegacyMethodologyRemainsReadable() {
        var graph = graph();
        item(graph, "HEADPHONES_APPLE", "EarPods USB-C", "1.000", null);
        assertQuantities(read(graph, false), "ACCESSORY_PODS_WATCH", "0", "1");
        assertQuantities(read(graph, true), "ACCESSORY_PODS_WATCH", "0", "0");
        for (boolean v4 : new boolean[]{false, true}) {
            assertQuantities(read(graph, v4), "ACCESSORY_AIRPODS", "0", "0");
        }
    }

    @Test
    void detailedDeviceCodesKeepApprovedBasesWithoutTreatingOtherBrandsAsApple() {
        var graph = graph();
        item(graph, "TABLET_APPLE", "iPad", "1.000", null);
        item(graph, "TABLET_OTHER", "Other tablet", "10.000", null);
        item(graph, "LAPTOP_APPLE", "MacBook", "2.000", null);
        item(graph, "LAPTOP_OTHER", "Other laptop", "10.000", null);
        item(graph, "WATCH_APPLE", "Apple Watch", "3.000", null);
        item(graph, "WATCH_SAMSUNG", "Samsung Watch", "10.000", null);
        item(graph, "WATCH_OTHER", "Other watch", "10.000", null);
        item(graph, "GAME_CONSOLES", "PlayStation 5", "4.000", null);
        item(graph, "GAME_CONSOLES", "Other console", "10.000", null);
        var rates = read(graph, true);
        assertQuantities(rates, "ACCESSORY_IPAD", "0", "1");
        assertQuantities(rates, "SETUP_SERVICE", "0", "6");
        assertQuantities(rates, "ACCESSORY_APPLE_WATCH", "0", "3");
        assertQuantities(rates, "PREMIUM_PROTECTION", "0", "10");
        assertQuantities(rates, "CHARGER_CABLE", "0", "0");
    }

    private static AttachRateAggregate find(List<AttachRateAggregate> rates, String code) {
        return rates.stream().filter(rate -> rate.metricCode().equals(code)).findFirst().orElseThrow();
    }

    private static void assertQuantities(List<AttachRateAggregate> rates, String code, String numerator, String denominator) {
        assertThat(find(rates, code).numeratorReceiptCount()).as(code + " numerator").isEqualByComparingTo(numerator);
        assertThat(find(rates, code).denominatorReceiptCount()).as(code + " denominator").isEqualByComparingTo(denominator);
    }

    private static List<AttachRateAggregate> read(Graph graph, boolean v4) {
        return transaction.execute(status -> {
            jdbc.execute("SET LOCAL jit = off");
            return new AttachRateRepository(new NamedParameterJdbcTemplate(jdbc), new AttachAttributionPolicy(v4))
                    .aggregate(graph.store(), START, END);
        });
    }

    private static Graph graph() {
        UUID connection = jdbc.queryForObject("SELECT id FROM integration_connections WHERE connection_key='livesklad-default'", UUID.class);
        var graph = new Graph(connection, UUID.randomUUID(), UUID.randomUUID());
        jdbc.update("INSERT INTO stores(id,connection_id,name) VALUES (?,?,'Synthetic detail store')", graph.store(), connection);
        jdbc.update("INSERT INTO sync_runs(id,connection_id,source_system,trigger_type,sync_scope,status) "
                + "VALUES (?,?,'LIVESKLAD','MANUAL','SALES','RUNNING')", graph.run(), connection);
        return graph;
    }

    private static Item item(Graph graph, String category, String name, String quantity, Item original) {
        var item = new Item(UUID.randomUUID(), UUID.randomUUID(), original == null ? UUID.randomUUID() : original.product());
        if (original == null) {
            jdbc.update("INSERT INTO products(id,connection_id,external_id,name,source_kind) VALUES (?,?,?,?,'PRODUCT')",
                    item.product(), graph.connection(), item.product().toString(), name);
        }
        String day = original == null ? "2026-09-01" : "2026-09-02";
        jdbc.update("INSERT INTO sales_documents(id,connection_id,external_id,store_id,original_document_id,"
                + "document_kind,source_document_type,occurred_at,business_date,net_amount,last_sync_run_id) "
                + "VALUES (?,?,?,?,?,?,'synthetic',?::timestamptz,?::date,100,?)", item.document(), graph.connection(),
                item.document().toString(), graph.store(), original == null ? null : original.document(),
                original == null ? "SALE" : "RETURN", day, day, graph.run());
        jdbc.update("INSERT INTO sales_document_items(id,sales_document_id,external_id,original_item_id,product_id,"
                + "product_name_snapshot,analytics_category_id,condition_type_snapshot,quantity,unit_price,"
                + "gross_amount,discount_amount,net_amount,cost_amount,cost_quality) "
                + "SELECT ?,?,?,?,?,?,id,'NEW',?::numeric,100,100,0,100,50,'KNOWN' FROM analytics_categories WHERE code=?",
                item.id(), item.document(), item.id().toString(), original == null ? null : original.id(),
                item.product(), name, quantity, category);
        return item;
    }

    private record Graph(UUID connection, UUID store, UUID run) { }
    private record Item(UUID id, UUID document, UUID product) { }
}
