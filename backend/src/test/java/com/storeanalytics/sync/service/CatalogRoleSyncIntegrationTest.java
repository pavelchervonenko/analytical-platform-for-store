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
@SpringBootTest(properties = {
        "app.catalog-compatibility.snapshots-enabled=true",
        "app.catalog-compatibility.snapshots-from=2026-01-01T00:00:00Z"
})
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

    private void sync(Fixture fixture, LiveSkladSaleSource source) {
        Instant at = source.summary().occurredAt();
        sales.synchronize(fixture.run(), new SalesSyncPeriod(at.minusSeconds(60), at.plusSeconds(60)),
                List.of(new StoreSalesBatch(fixture.store(), List.of(source))));
    }

    private LiveSkladSaleSource source(Fixture fixture, Instant at, String name, boolean invalidSecond) {
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
        compatibility.append(compatibility.observe(product, false), 1,
                new Request(Action.CONFIRM, Coverage.EXCLUSIVE, List.of(Target.APPLE_WATCH),
                        "Synthetic confirmation", Origin.DIRECT_REVIEW, null, null), actor);
        return new Fixture(actor, product, stores.findById(store).orElseThrow(), run, UUID.randomUUID().toString());
    }

    private record Fixture(UUID actor, UUID product, Store store, UUID run, String sale) { }
}
