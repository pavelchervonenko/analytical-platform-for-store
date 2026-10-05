package com.storeanalytics.common.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class TemporalSellerAttachMigrationIntegrationTest {
    @Test
    void upgradeAddsProvenanceWithoutChangingExistingFactsDefinitionsOrWarrantyAllocations() {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")) {
            postgres.start();
            var source = new DriverManagerDataSource(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword());
            Flyway.configure().dataSource(source).locations("classpath:db/migration").target("94").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(source);
            seed(jdbc);
            List<String> before = jdbc.queryForList(
                    "SELECT to_jsonb(fact)::text FROM attach_rate_item_facts_v4_catalog fact", String.class);
            List<String> allocations = jdbc.queryForList(
                    "SELECT to_jsonb(fact)::text FROM warranty_attach_effective_allocations fact", String.class);
            String definition = jdbc.queryForObject(
                    "SELECT pg_get_viewdef('attach_rate_item_facts_v4_catalog'::regclass, true)", String.class);
            List<Map<String, Object>> finance = jdbc.queryForList(
                    "SELECT id,employee_id,net_amount,cost_amount FROM sales_documents ORDER BY id");
            assertThat(before).isNotEmpty();
            assertThat(allocations).isNotEmpty();
            Flyway migration = Flyway.configure().dataSource(source).locations("classpath:db/migration")
                    .target("95").load();
            assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(jdbc.queryForList(
                    "SELECT to_jsonb(fact)::text FROM attach_rate_item_facts_v4_catalog fact", String.class))
                    .containsExactlyInAnyOrderElementsOf(before);
            assertThat(jdbc.queryForList(
                    "SELECT to_jsonb(fact)::text FROM warranty_attach_effective_allocations fact", String.class))
                    .containsExactlyInAnyOrderElementsOf(allocations);
            assertThat(jdbc.queryForObject(
                    "SELECT pg_get_viewdef('attach_rate_item_facts_v4_catalog'::regclass, true)", String.class))
                    .isEqualTo(definition);
            assertThat(jdbc.queryForList(
                    "SELECT id,employee_id,net_amount,cost_amount FROM sales_documents ORDER BY id"))
                    .isEqualTo(finance);
            String columns = "store_id,business_date,employee_id,net_quantity,numerator_metric_code,device_role,"
                    + "denominator_metric_codes,classification_issue_code,numerator_metric_codes";
            assertThat(jdbc.queryForList("SELECT to_jsonb(fact)::text FROM (SELECT " + columns
                    + " FROM seller_attach_item_facts_v1) fact", String.class))
                    .containsExactlyInAnyOrderElementsOf(before);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM seller_attach_item_facts_v1 "
                    + "WHERE membership_document_id IS NULL OR source_item_id IS NULL OR membership_at IS NULL "
                    + "OR membership_document_kind IS NULL OR membership_basis IS NULL", Long.class)).isZero();
            migration.validate();
            assertThat(migration.migrate().migrationsExecuted).isZero();
        }
    }

    private void seed(JdbcTemplate jdbc) {
        jdbc.execute("""
                INSERT INTO stores(id,connection_id,source_system,external_id,name)
                SELECT '00000000-0000-0000-0000-000000009501',id,'LIVESKLAD','temporal-store','Synthetic'
                FROM integration_connections WHERE connection_key='livesklad-default';
                INSERT INTO employees(id,connection_id,source_system,external_id,full_name)
                SELECT '00000000-0000-0000-0000-000000009502',id,'LIVESKLAD','temporal-seller','Synthetic'
                FROM integration_connections WHERE connection_key='livesklad-default';
                INSERT INTO sync_runs(id,connection_id,source_system,trigger_type,sync_scope,status,finished_at)
                SELECT '00000000-0000-0000-0000-000000009503',id,'LIVESKLAD','MANUAL','SALES','SUCCESS',now()
                FROM integration_connections WHERE connection_key='livesklad-default';
                INSERT INTO products(id,connection_id,source_system,external_id,name)
                SELECT '00000000-0000-0000-0000-000000009504',id,'LIVESKLAD','temporal-product','Synthetic'
                FROM integration_connections WHERE connection_key='livesklad-default';
                INSERT INTO sales_documents(id,connection_id,external_id,store_id,employee_id,
                    document_kind,source_document_type,occurred_at,business_date,net_amount,last_sync_run_id)
                SELECT '00000000-0000-0000-0000-000000009505',id,'temporal-sale',
                    '00000000-0000-0000-0000-000000009501','00000000-0000-0000-0000-000000009502',
                    'SALE','sale','2026-09-15T12:00Z','2026-09-15',100,
                    '00000000-0000-0000-0000-000000009503'
                FROM integration_connections WHERE connection_key='livesklad-default';
                INSERT INTO sales_document_items(id,sales_document_id,external_id,product_id,
                    product_name_snapshot,analytics_category_id,condition_type_snapshot,quantity,
                    unit_price,gross_amount,discount_amount,net_amount,cost_amount,cost_quality)
                SELECT '00000000-0000-0000-0000-000000009506',
                    '00000000-0000-0000-0000-000000009505','temporal-device',
                    '00000000-0000-0000-0000-000000009504','Synthetic device',id,'NEW',1,100,100,0,100,50,'KNOWN'
                FROM analytics_categories WHERE code='IPHONE_NEW_ASIS';
                INSERT INTO sales_document_items(id,sales_document_id,external_id,product_id,
                    product_name_snapshot,analytics_category_id,condition_type_snapshot,quantity,
                    unit_price,gross_amount,discount_amount,net_amount,cost_amount,cost_quality)
                SELECT '00000000-0000-0000-0000-000000009507',
                    '00000000-0000-0000-0000-000000009505','temporal-warranty',
                    '00000000-0000-0000-0000-000000009504','Check new',id,'NOT_APPLICABLE',1,10,10,0,10,0,'KNOWN'
                FROM analytics_categories WHERE code='WARRANTY_GENERIC';
                INSERT INTO sales_document_items(id,sales_document_id,external_id,product_id,
                    product_name_snapshot,analytics_category_id,condition_type_snapshot,quantity,
                    unit_price,gross_amount,discount_amount,net_amount,cost_amount,cost_quality)
                SELECT '00000000-0000-0000-0000-000000009508',
                    '00000000-0000-0000-0000-000000009505','temporal-film',
                    '00000000-0000-0000-0000-000000009504','Synthetic film',id,'NOT_APPLICABLE',1,10,10,0,10,0,'KNOWN'
                FROM analytics_categories WHERE code='FILM_PHONE';
                """);
    }
}
