package com.storeanalytics.interpretation.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class LlmOperationsQueryIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private LlmOperationsQuery query;

    @Autowired
    private LlmOperationsControlStore controlStore;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void emptyQueueProducesAStableSanitizedSummary() {
        LlmOperationsView view = query.get(50);

        assertThat(view.summary().attentionLevel()).isEqualTo("NORMAL");
        assertThat(view.summary().pending()).isZero();
        assertThat(view.summary().knownCostLast30Days()).isZero();
        assertThat(view.incidents()).isEmpty();
        assertThat(view.configuration().providerConfigured()).isFalse();
    }

    @Test
    void unknownSnapshotIsReportedAsANotFoundBusinessState() {
        assertThatThrownBy(() -> controlStore.lockRegenerationTarget(UUID.randomUUID()))
                .isInstanceOf(LlmOperationsNotFoundException.class);
    }
}
