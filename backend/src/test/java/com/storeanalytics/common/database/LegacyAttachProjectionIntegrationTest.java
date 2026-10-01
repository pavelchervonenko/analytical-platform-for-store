package com.storeanalytics.common.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Compares the fourteen published attach metrics with the prospective catalog projection. */
@Testcontainers(disabledWithoutDocker = true)
class LegacyAttachProjectionIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void previouslySavedCategoriesKeepPublishedAttachNumeratorsAndDenominators() throws Exception {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();

        String published = new String(getClass().getResourceAsStream(
                "/db/migration/V38__attach_rate_units_methodology.sql").readAllBytes(),
                StandardCharsets.UTF_8);
        String publishedView = published.substring(
                published.indexOf("CREATE VIEW attach_rate_item_facts_v3 AS"),
                published.indexOf("COMMENT ON VIEW attach_rate_item_facts_v3"))
                .replace("CREATE VIEW attach_rate_item_facts_v3 AS",
                        "CREATE VIEW attach_rate_item_facts_v3_published AS");

        UUID store = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        UUID sale = UUID.randomUUID();
        UUID returned = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute(publishedView);
            statement.execute("""
                    INSERT INTO stores(id,connection_id,source_system,external_id,name)
                    SELECT '%s',id,'LIVESKLAD','legacy-attach-store','Legacy attach store'
                    FROM integration_connections WHERE connection_key='livesklad-default'
                    """.formatted(store));
            statement.execute("""
                    INSERT INTO sync_runs(id,connection_id,source_system,trigger_type,sync_scope,status,
                        started_at,finished_at)
                    SELECT '%s',id,'LIVESKLAD','MANUAL','SALES','SUCCESS',now(),now()
                    FROM integration_connections WHERE connection_key='livesklad-default'
                    """.formatted(run));
            statement.execute("""
                    INSERT INTO products(id,connection_id,source_system,external_id,name,source_kind)
                    SELECT '%s',id,'LIVESKLAD','legacy-attach-product','Legacy attach product','PRODUCT'
                    FROM integration_connections WHERE connection_key='livesklad-default'
                    """.formatted(product));
            statement.execute("""
                    INSERT INTO sales_documents(id,connection_id,source_system,external_id,store_id,
                        document_kind,source_document_type,occurred_at,business_date,net_amount,
                        is_deleted,last_sync_run_id)
                    SELECT '%s',id,'LIVESKLAD','legacy-attach-sale','%s','SALE','sale',
                        '2026-07-01T10:00:00Z','2026-07-01',0,false,'%s'
                    FROM integration_connections WHERE connection_key='livesklad-default'
                    """.formatted(sale, store, run));
            statement.execute("""
                    INSERT INTO sales_documents(id,connection_id,source_system,external_id,store_id,
                        original_document_id,document_kind,source_document_type,occurred_at,
                        business_date,net_amount,is_deleted,last_sync_run_id)
                    SELECT '%s',id,'LIVESKLAD','legacy-attach-return','%s','%s','RETURN','sale',
                        '2026-07-02T10:00:00Z','2026-07-02',0,false,'%s'
                    FROM integration_connections WHERE connection_key='livesklad-default'
                    """.formatted(returned, store, sale, run));
            insertItem(statement, sale, product, "phone", "IPHONE_NEW_ASIS", "NEW", "1.000");
            insertItem(statement, sale, product, "case", "CASE_APPLE_IPHONE", "NOT_APPLICABLE", "2.000");
            insertItem(statement, sale, product, "charge", "CHARGER_CABLE", "NOT_APPLICABLE", "1.000");
            insertItem(statement, sale, product, "charging adapter", "OTHER_ACCESSORY_PRODUCT",
                    "NOT_APPLICABLE", "1.000");
            insertItem(statement, sale, product, "AirPods Pro", "PODS_WATCH_OTHER_DEVICE", "NEW", "1.000");
            insertItem(statement, sale, product, "AirPods case", "ACCESSORY_PODS_WATCH",
                    "NOT_APPLICABLE", "1.000");
            insertItem(statement, returned, product, "case-return", "CASE_APPLE_IPHONE",
                    "NOT_APPLICABLE", "1.000");

            List<String> baseline = aggregate(statement, "attach_rate_item_facts_v3_published", store);
            List<String> prospective = aggregate(statement, "attach_rate_item_facts_v3_catalog", store);
            assertThat(prospective).containsExactlyElementsOf(baseline);
        }
    }

    private void insertItem(java.sql.Statement statement, UUID document, UUID product,
                            String name, String category, String condition, String quantity) throws Exception {
        statement.execute("""
                INSERT INTO sales_document_items(sales_document_id,external_id,product_id,
                    product_name_snapshot,analytics_category_id,condition_type_snapshot,
                    quantity,unit_price,gross_amount,discount_amount,net_amount,
                    cost_amount,cost_quality,is_deleted)
                SELECT '%s','%s','%s','%s',id,'%s',%s,0,0,0,0,0,'KNOWN',false
                FROM analytics_categories WHERE code='%s'
                """.formatted(document, name, product, name, condition, quantity, category));
    }

    private List<String> aggregate(java.sql.Statement statement, String view, UUID store) throws Exception {
        List<String> rows = new ArrayList<>();
        try (ResultSet result = statement.executeQuery("""
                SELECT definition.metric_code,
                    COALESCE(sum(fact.net_quantity) FILTER (
                        WHERE definition.metric_code=fact.numerator_metric_code),0) AS numerator,
                    COALESCE(sum(fact.net_quantity) FILTER (
                        WHERE definition.metric_code=ANY(fact.denominator_metric_codes)),0) AS denominator
                FROM attach_rate_metric_definitions_v3 definition
                LEFT JOIN %s fact ON fact.store_id='%s'
                WHERE definition.sort_order<=14
                GROUP BY definition.sort_order,definition.metric_code
                ORDER BY definition.sort_order
                """.formatted(view, store))) {
            while (result.next()) {
                rows.add(result.getString(1) + ':' + result.getBigDecimal(2).toPlainString()
                        + ':' + result.getBigDecimal(3).toPlainString());
            }
        }
        return rows;
    }
}
