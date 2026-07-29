package com.storeanalytics.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.integration.connection.model.IntegrationConnection;
import com.storeanalytics.integration.connection.repository.IntegrationConnectionRepository;
import com.storeanalytics.product.exception.ProductIdentityConflictException;
import com.storeanalytics.product.model.ProductDetails;
import com.storeanalytics.product.model.ProductSourceKind;
import com.storeanalytics.product.service.LiveSkladProductIdentityResolver.CatalogResolution;
import com.storeanalytics.product.service.LiveSkladProductIdentityResolver.ProductIdentityResolution;
import com.storeanalytics.sync.model.SourceSystem;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class LiveSkladProductIdentityResolverIntegrationTest {

    private static final String DEFAULT_CONNECTION = "livesklad-default";
    private static final String PRODUCT_CODE = "4310";
    private static final String PRODUCT_EXTERNAL_ID = "product-4310";
    private static final Instant OBSERVED_AT =
            Instant.parse("2026-07-20T10:15:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private LiveSkladProductIdentityResolver resolver;

    @Autowired
    private IntegrationConnectionRepository connectionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactions;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void cleanProducts() {
        jdbcTemplate.update("DELETE FROM product_category_assignments");
        jdbcTemplate.update("DELETE FROM products");
        jdbcTemplate.update(
                "DELETE FROM integration_connections "
                        + "WHERE connection_key <> 'livesklad-default'"
        );
        transactions = new TransactionTemplate(transactionManager);
    }

    @Test
    void catalogThenObservationClaimsTheSameProduct() {
        UUID provisionalId = resolveCatalog(DEFAULT_CONNECTION).productsByIdentifier()
                .get(PRODUCT_CODE)
                .getId();

        ProductIdentityResolution observed = resolveObserved(
                DEFAULT_CONNECTION,
                PRODUCT_EXTERNAL_ID
        );

        assertThat(observed.product().getId()).isEqualTo(provisionalId);
        assertThat(observed.kind())
                .isEqualTo(LiveSkladProductIdentityResolver.ResolutionKind.UPDATED);
        assertThat(productCount(DEFAULT_CONNECTION)).isOne();
        assertThat(productExternalId(provisionalId))
                .isEqualTo(PRODUCT_EXTERNAL_ID);
    }

    @Test
    void observationThenCatalogResolvesTheSameProduct() {
        UUID observedId = resolveObserved(
                DEFAULT_CONNECTION,
                PRODUCT_EXTERNAL_ID
        ).product().getId();

        CatalogResolution catalog = resolveCatalog(DEFAULT_CONNECTION);

        assertThat(catalog.createdCount()).isZero();
        assertThat(catalog.productsByIdentifier().get(PRODUCT_CODE).getId())
                .isEqualTo(observedId);
        assertThat(productCount(DEFAULT_CONNECTION)).isOne();
    }

    @Test
    void productCodeIsScopedByIntegrationConnection() {
        transactions.executeWithoutResult(status -> connectionRepository.save(
                new IntegrationConnection(
                        "livesklad-second",
                        SourceSystem.LIVESKLAD,
                        "Second LiveSklad",
                        "https://second.example.invalid",
                        "env:SECOND_LIVESKLAD"
                )
        ));

        UUID defaultProduct = resolveCatalog(DEFAULT_CONNECTION)
                .productsByIdentifier().get(PRODUCT_CODE).getId();
        UUID secondProduct = resolveCatalog("livesklad-second")
                .productsByIdentifier().get(PRODUCT_CODE).getId();

        assertThat(defaultProduct).isNotEqualTo(secondProduct);
        assertThat(productCount(DEFAULT_CONNECTION)).isOne();
        assertThat(productCount("livesklad-second")).isOne();
    }

    @Test
    void finalProductCodeCannotBeClaimedByAnotherExternalIdentity() {
        resolveObserved(DEFAULT_CONNECTION, PRODUCT_EXTERNAL_ID);

        assertThatThrownBy(() -> resolveObserved(
                DEFAULT_CONNECTION,
                "different-external-id"
        )).isInstanceOf(ProductIdentityConflictException.class)
                .hasMessageContaining("conflicts with an existing product");

        assertThat(productCount(DEFAULT_CONNECTION)).isOne();
    }

    @Test
    void concurrentObservationsCreateOnlyOneProduct() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<UUID> first = executor.submit(() -> concurrentObservation(
                    ready,
                    start
            ));
            Future<UUID> second = executor.submit(() -> concurrentObservation(
                    ready,
                    start
            ));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS))
                    .isEqualTo(second.get(10, TimeUnit.SECONDS));
            assertThat(productCount(DEFAULT_CONNECTION)).isOne();
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private UUID concurrentObservation(
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent test did not start");
        }
        return resolveObserved(DEFAULT_CONNECTION, PRODUCT_EXTERNAL_ID)
                .product()
                .getId();
    }

    private CatalogResolution resolveCatalog(String connectionKey) {
        return transactions.execute(status -> resolver.resolveCatalogReferences(
                connection(connectionKey),
                Map.of(PRODUCT_CODE, "Fixture Cable")
        ));
    }

    private ProductIdentityResolution resolveObserved(String connectionKey, String externalId) {
        return transactions.execute(status -> resolver.resolveObservedProduct(
                connection(connectionKey),
                externalId,
                new ProductDetails(
                        null,
                        PRODUCT_CODE,
                        "SKU-4310",
                        "Fixture Cable",
                        ProductSourceKind.PRODUCT,
                        OBSERVED_AT
                )
        ));
    }

    private IntegrationConnection connection(String connectionKey) {
        return connectionRepository.findByConnectionKeyAndActiveTrue(connectionKey)
                .orElseThrow();
    }

    private int productCount(String connectionKey) {
        return jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM products product
                JOIN integration_connections connection
                  ON connection.id = product.connection_id
                WHERE connection.connection_key = ?
                """,
                Integer.class,
                connectionKey
        );
    }

    private String productExternalId(UUID productId) {
        return jdbcTemplate.queryForObject(
                "SELECT external_id FROM products WHERE id = ?",
                String.class,
                productId
        );
    }
}
