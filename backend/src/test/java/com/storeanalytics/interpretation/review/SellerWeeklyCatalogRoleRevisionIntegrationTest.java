package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class SellerWeeklyCatalogRoleRevisionIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void migrationAndLateRoleInsertionInvalidateOnlyAffectedSellerSource() {
        migrate("86");
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbc = new JdbcTemplate(dataSource);
        UUID connection = jdbc.queryForObject(
                "SELECT id FROM integration_connections WHERE connection_key = 'livesklad-default'", UUID.class);
        UUID target = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        jdbc.update("INSERT INTO stores(id,connection_id,name) VALUES (?,?,?)",
                target, connection, "Synthetic target");
        jdbc.update("INSERT INTO stores(id,connection_id,name) VALUES (?,?,?)",
                other, connection, "Synthetic other");

        UUID item = saleItem(jdbc, connection, target);
        long targetBefore = revision(jdbc, target);
        long otherBefore = revision(jdbc, other);

        migrate("87");
        long targetAfterMigration = revision(jdbc, target);
        long otherAfterMigration = revision(jdbc, other);
        assertThat(targetAfterMigration).isEqualTo(targetBefore + 1);
        assertThat(otherAfterMigration).isEqualTo(otherBefore + 1);

        String externalProduct = jdbc.queryForObject("""
                SELECT product.external_id FROM products product
                JOIN sales_document_items sale ON sale.product_id = product.id
                WHERE sale.id = ?
                """, String.class, item);
        jdbc.update("""
                INSERT INTO catalog_sale_role_snapshots
                    (item_id,fact_identity,monetary_category,policy_version,registry_sha256,
                     outcome,reason,origin,observation_fingerprint,
                     observed_connection_key,observed_external_id)
                VALUES (?,catalog_sale_role_fact(?),'CHARGER_CABLE','catalog-accessory-roles-v1',
                    ?,'NO_CONTRIBUTION','SYNTHETIC','SALE_PROJECTION',?,'livesklad-default',?)
                """, item, item, "a".repeat(64), "b".repeat(64), externalProduct);

        assertThat(revision(jdbc, target)).isEqualTo(targetAfterMigration + 1);
        assertThat(revision(jdbc, other)).isEqualTo(otherAfterMigration);
    }

    private void migrate(String target) {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
    }

    private long revision(JdbcTemplate jdbc, UUID store) {
        return jdbc.queryForObject("""
                SELECT COALESCE((SELECT revision FROM store_analytics_source_state
                                 WHERE store_id = ?), 0)
                """, Long.class, store);
    }

    private UUID saleItem(JdbcTemplate jdbc, UUID connection, UUID store) {
        UUID sync = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        UUID document = UUID.randomUUID();
        UUID item = UUID.randomUUID();
        Instant occurred = Instant.parse("2026-08-01T12:00:00Z");
        jdbc.update("""
                INSERT INTO sync_runs(id,connection_id,source_system,trigger_type,sync_scope,status)
                VALUES (?,?,'LIVESKLAD','MANUAL','SALES','RUNNING')
                """, sync, connection);
        jdbc.update("""
                INSERT INTO products(id,connection_id,external_id,code,name,source_kind)
                VALUES (?,?,?,?,?,'PRODUCT')
                """, product, connection, product.toString(), "synthetic", "Synthetic charger");
        jdbc.update("""
                INSERT INTO sales_documents(id,connection_id,external_id,store_id,document_kind,
                    source_document_type,occurred_at,business_date,net_amount,last_sync_run_id)
                VALUES (?,?,?,?,'SALE','synthetic',?,?,100,?)
                """, document, connection, document.toString(), store, Timestamp.from(occurred),
                Date.valueOf(LocalDate.of(2026, 8, 1)), sync);
        jdbc.update("""
                INSERT INTO sales_document_items(id,sales_document_id,external_id,product_id,
                    product_name_snapshot,analytics_category_id,condition_type_snapshot,
                    quantity,unit_price,gross_amount,discount_amount,net_amount,cost_amount,cost_quality)
                SELECT ?,?,?,?,'Synthetic charger',id,'NOT_APPLICABLE',1,100,100,0,100,50,'KNOWN'
                FROM analytics_categories WHERE code = 'CHARGER_CABLE'
                """, item, document, item.toString(), product);
        return item;
    }
}
