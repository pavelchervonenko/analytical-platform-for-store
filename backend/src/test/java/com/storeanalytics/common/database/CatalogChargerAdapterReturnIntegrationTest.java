package com.storeanalytics.common.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** A linked return after activation retains the charging policy of its original sale. */
@Testcontainers(disabledWithoutDocker = true)
class CatalogChargerAdapterReturnIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void oldAdapterSaleAndLaterLinkedReturnCancelWhileNewGenericAdapterDoesNotAttach()
            throws Exception {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()).locations("classpath:db/migration").load().migrate();
        UUID store = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        UUID oldSale = UUID.randomUUID();
        UUID oldItem = UUID.randomUUID();
        UUID returned = UUID.randomUUID();
        UUID freshSale = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO catalog_classification_activation(singleton,activate_from)
                    VALUES (true,'2030-01-01T00:00:00Z')
                    """);
            statement.execute("""
                    INSERT INTO stores(id,connection_id,source_system,external_id,name)
                    SELECT '%s',id,'LIVESKLAD','adapter-cutover-store','Adapter cutover store'
                    FROM integration_connections WHERE connection_key='livesklad-default'
                    """.formatted(store));
            statement.execute("""
                    INSERT INTO sync_runs(id,connection_id,source_system,trigger_type,sync_scope,
                        status,started_at,finished_at)
                    SELECT '%s',id,'LIVESKLAD','MANUAL','SALES','SUCCESS',now(),now()
                    FROM integration_connections WHERE connection_key='livesklad-default'
                    """.formatted(run));
            statement.execute("""
                    INSERT INTO products(id,connection_id,source_system,external_id,name,source_kind)
                    SELECT '%s',id,'LIVESKLAD','adapter-cutover-product','Переходник USB-C','PRODUCT'
                    FROM integration_connections WHERE connection_key='livesklad-default'
                    """.formatted(product));
            insertDocument(statement, oldSale, store, run, null, "SALE", "2026-09-01T10:00:00Z");
            insertDocument(statement, returned, store, run, oldSale, "RETURN", "2030-01-02T10:00:00Z");
            insertDocument(statement, freshSale, store, run, null, "SALE", "2030-01-02T11:00:00Z");
            insertItem(statement, oldItem, oldSale, product, null);
            insertItem(statement, UUID.randomUUID(), returned, product, oldItem);
            insertItem(statement, UUID.randomUUID(), freshSale, product, null);

            try (var result = statement.executeQuery("""
                    SELECT business_date::text,
                           sum(net_quantity) FILTER (WHERE numerator_metric_code='CHARGER_CABLE')
                    FROM attach_rate_item_facts_v4_catalog
                    WHERE store_id='%s'
                    GROUP BY business_date ORDER BY business_date
                    """.formatted(store))) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("2026-09-01");
                assertThat(result.getBigDecimal(2)).isEqualByComparingTo(BigDecimal.ONE);
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("2030-01-02");
                assertThat(result.getBigDecimal(2)).isEqualByComparingTo(BigDecimal.ONE.negate());
                assertThat(result.next()).isFalse();
            }
        }
    }

    private void insertDocument(java.sql.Statement statement, UUID id, UUID store, UUID run,
                                UUID original, String kind, String occurredAt) throws Exception {
        String originalSql = original == null ? "NULL" : "'" + original + "'::uuid";
        statement.execute("""
                INSERT INTO sales_documents(id,connection_id,source_system,external_id,store_id,
                    original_document_id,document_kind,source_document_type,occurred_at,
                    business_date,net_amount,is_deleted,last_sync_run_id)
                SELECT '%s',id,'LIVESKLAD','%s','%s',%s,'%s','sale','%s',
                       ('%s'::timestamptz AT TIME ZONE 'UTC')::date,0,false,'%s'
                FROM integration_connections WHERE connection_key='livesklad-default'
                """.formatted(id, id, store, originalSql, kind, occurredAt, occurredAt, run));
    }

    private void insertItem(java.sql.Statement statement, UUID id, UUID document,
                            UUID product, UUID original) throws Exception {
        String originalSql = original == null ? "NULL" : "'" + original + "'::uuid";
        statement.execute("""
                INSERT INTO sales_document_items(id,sales_document_id,external_id,product_id,
                    product_name_snapshot,analytics_category_id,condition_type_snapshot,
                    quantity,unit_price,gross_amount,discount_amount,net_amount,
                    cost_amount,cost_quality,is_deleted,original_item_id)
                SELECT '%s','%s','%s','%s','Переходник USB-C',id,'NOT_APPLICABLE',
                       1,0,0,0,0,0,'KNOWN',false,%s
                FROM analytics_categories WHERE code='OTHER_ACCESSORY_PRODUCT'
                """.formatted(id, document, id, product, originalSql));
    }
}
