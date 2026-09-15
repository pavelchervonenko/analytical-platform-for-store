package com.storeanalytics.integration.livesklad.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {
        "app.sync.worker-enabled=false",
        "app.sync.schedule-enabled=false",
        "app.livesklad.webhook.worker.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class LiveSkladWebhookStoreIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private LiveSkladWebhookStore store;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void cleanInbox() {
        jdbcTemplate.update("DELETE FROM livesklad_webhook_receipts");
    }

    @Test
    void claimsEachWebhookKindOnlyThroughItsDedicatedWorker() {
        store.record(receipt(
                LiveSkladWebhookKind.ORDER_RETURN,
                "order-event",
                "order-1"
        ));
        store.record(receipt(
                LiveSkladWebhookKind.SALE_RETURN,
                "sale-event",
                "return-1"
        ));

        LiveSkladWebhookClaim first = store.claimNextSaleReturn(
                "worker-1", NOW, Duration.ofMinutes(2), 8
        ).orElseThrow();

        assertThat(first.eventId()).isEqualTo("sale-event");
        assertThat(first.attemptCount()).isEqualTo(1);
        store.recordSourceDocument(first.id(), "worker-1", "return-1");
        store.retry(
                first.id(),
                "worker-1",
                NOW.plusSeconds(30),
                "LIVESKLAD_HTTP_404",
                "temporary"
        );

        assertThat(store.claimNextSaleReturn(
                "worker-1",
                NOW.plusSeconds(29),
                Duration.ofMinutes(2),
                8
        )).isEmpty();

        LiveSkladWebhookClaim second = store.claimNextSaleReturn(
                "worker-2",
                NOW.plusSeconds(30),
                Duration.ofMinutes(2),
                8
        ).orElseThrow();
        assertThat(second.attemptCount()).isEqualTo(2);
        store.complete(second.id(), "worker-2", NOW.plusSeconds(31));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                """
                SELECT processing_status,
                       source_document_id,
                       processed_at IS NOT NULL AS processed,
                       terminal_failure
                FROM livesklad_webhook_receipts
                WHERE event_id = 'sale-event'
                """
        );
        assertThat(row)
                .containsEntry("processing_status", "PROCESSED")
                .containsEntry("source_document_id", "return-1")
                .containsEntry("processed", true)
                .containsEntry("terminal_failure", false);
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT processing_status
                FROM livesklad_webhook_receipts
                WHERE event_id = 'order-event'
                """,
                String.class
        )).isEqualTo("RECEIVED");
        LiveSkladWebhookClaim orderClaim = store.claimNextOrderReturn(
                "order-worker", NOW.plusSeconds(32), Duration.ofMinutes(2), 8
        ).orElseThrow();
        assertThat(orderClaim.eventId()).isEqualTo("order-event");
        store.recordSourceDocument(
                orderClaim.id(), "order-worker", "order-1"
        );
        store.complete(
                orderClaim.id(), "order-worker", NOW.plusSeconds(33)
        );

        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT processing_status
                FROM livesklad_webhook_receipts
                WHERE event_id = 'order-event'
                """,
                String.class
        )).isEqualTo("PROCESSED");

    }

    @Test
    void queuesValidatedRecoveryAndExposesExpectationsToWorker() {
        UUID requestedBy = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO app_users (
                    id, email, password_hash, display_name, role
                ) VALUES (?, ?, 'hash', 'Recovery Admin', 'ADMIN')
                """,
                requestedBy,
                requestedBy + "@example.com"
        );
        LiveSkladReturnRecoveryView queued = store.createRecovery(
                new LiveSkladReturnRecoveryRequest(
                        UUID.randomUUID(),
                        requestedBy,
                        "recovery-F000381",
                        "6a6daeadaa17fa79fe127335",
                        "F000381",
                        new BigDecimal("15030.00"),
                        2,
                        LiveSkladReturnRecoveryMode.MISSING_RETURN,
                        null,
                        null,
                        null,
                        List.of(),
                        "Restore report discrepancy",
                        "manual-recovery-event",
                        """
                        {"eventId":"manual-recovery-event","data":{"id":"6a6daeadaa17fa79fe127335"}}
                        """,
                        "b".repeat(64),
                        NOW
                )
        );

        assertThat(queued.status()).isEqualTo("RECEIVED");
        LiveSkladWebhookClaim claim = store.claimNextSaleReturn(
                "recovery-worker", NOW, Duration.ofMinutes(2), 8
        ).orElseThrow();
        assertThat(claim.recovery()).isTrue();
        assertThat(claim.sourceDocumentId())
                .isEqualTo("6a6daeadaa17fa79fe127335");
        assertThat(claim.recoveryExpectedDocumentNumber()).isEqualTo("F000381");
        assertThat(claim.recoveryExpectedNetAmount())
                .isEqualByComparingTo("15030.00");
        assertThat(claim.recoveryExpectedPositionCount()).isEqualTo(2);

        store.complete(claim.id(), "recovery-worker", NOW.plusSeconds(1));

        LiveSkladReturnRecoveryView completed =
                store.findRecoveryById(queued.id()).orElseThrow();
        assertThat(completed.status()).isEqualTo("PROCESSED");
        assertThat(completed.processedAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void persistsZeroNetRecoveryExpectationForWorker() {
        UUID requestedBy = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO app_users (
                    id, email, password_hash, display_name, role
                ) VALUES (?, ?, 'hash', 'Zero-net Recovery Admin', 'ADMIN')
                """,
                requestedBy,
                requestedBy + "@example.com"
        );
        LiveSkladReturnRecoveryView queued = store.createRecovery(
                new LiveSkladReturnRecoveryRequest(
                        UUID.randomUUID(),
                        requestedBy,
                        "recovery-F000175",
                        "69c67dbf35f1a26e5b3bc638",
                        "F000175",
                        new BigDecimal("0.00"),
                        1,
                        LiveSkladReturnRecoveryMode.MISSING_RETURN,
                        null,
                        null,
                        null,
                        List.of(),
                        "Restore verified zero-net item return",
                        "manual-zero-net-recovery-event",
                        """
                        {"eventId":"manual-zero-net-recovery-event","data":{"id":"69c67dbf35f1a26e5b3bc638"}}
                        """,
                        "d".repeat(64),
                        NOW
                )
        );

        LiveSkladWebhookClaim claim = store.claimNextSaleReturn(
                "zero-net-recovery-worker", NOW, Duration.ofMinutes(2), 8
        ).orElseThrow();

        assertThat(queued.expectedNetAmount()).isEqualByComparingTo("0.00");
        assertThat(claim.recoveryExpectedNetAmount())
                .isEqualByComparingTo("0.00");
        assertThat(claim.recoveryExpectedPositionCount()).isEqualTo(1);
    }

    @Test
    void persistsExistingOrphanRelinkExpectationsForWorker() {
        UUID requestedBy = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO app_users (
                    id, email, password_hash, display_name, role
                ) VALUES (?, ?, 'hash', 'Relink Admin', 'ADMIN')
                """,
                requestedBy,
                requestedBy + "@example.com"
        );
        RecoverLiveSkladReturnLinkExpectation link =
                new RecoverLiveSkladReturnLinkExpectation(
                        "6a5ce976c3093727ca1a0af0",
                        "69875b2ba44502026430bc2d",
                        "695bd5e1214c11471133b70a",
                        new BigDecimal("1.000"),
                        new BigDecimal("46990.00"),
                        new BigDecimal("43750.00")
                );
        LiveSkladReturnRecoveryView queued = store.createRecovery(
                new LiveSkladReturnRecoveryRequest(
                        UUID.randomUUID(),
                        requestedBy,
                        "relink-F000349-v1",
                        "6a5ce976c30937c4371a0af1",
                        "F000349",
                        new BigDecimal("46990.00"),
                        1,
                        LiveSkladReturnRecoveryMode.EXISTING_ORPHAN_RELINK,
                        "6912f4ab09e647f3125d14ba",
                        "69875ed7a44502f84130f263",
                        "6912f4ab09e647f3125d14ba",
                        List.of(link),
                        "Relink verified existing orphan return",
                        "manual-relink-event",
                        """
                        {"eventId":"manual-relink-event","data":{"id":"6a5ce976c30937c4371a0af1"}}
                        """,
                        "c".repeat(64),
                        NOW
                )
        );

        assertThat(queued.mode()).isEqualTo(
                LiveSkladReturnRecoveryMode.EXISTING_ORPHAN_RELINK
        );
        assertThat(queued.expectedCurrentEmployeeExternalId())
                .isEqualTo("6912f4ab09e647f3125d14ba");
        assertThat(queued.expectedOriginalLinks()).containsExactly(link);
        LiveSkladWebhookClaim claim = store.claimNextSaleReturn(
                "relink-worker", NOW, Duration.ofMinutes(2), 8
        ).orElseThrow();
        assertThat(claim.existingOrphanRelink()).isTrue();
        assertThat(claim.recoveryExpectedCurrentEmployeeExternalId())
                .isEqualTo("6912f4ab09e647f3125d14ba");
        assertThat(claim.recoveryExpectedOriginalSaleExternalId())
                .isEqualTo("69875ed7a44502f84130f263");
        assertThat(claim.recoveryExpectedOriginalEmployeeExternalId())
                .isEqualTo("6912f4ab09e647f3125d14ba");
        assertThat(claim.recoveryExpectedOriginalLinks())
                .containsExactly(link);
    }

    private LiveSkladWebhookReceipt receipt(
            LiveSkladWebhookKind kind,
            String eventId,
            String documentId
    ) {
        return new LiveSkladWebhookReceipt(
                kind,
                eventId,
                "action-" + eventId,
                "return",
                "Return",
                """
                {"eventId":"%s","data":{"id":"%s"}}
                """.formatted(eventId, documentId),
                "a".repeat(64),
                NOW
        );
    }
}
