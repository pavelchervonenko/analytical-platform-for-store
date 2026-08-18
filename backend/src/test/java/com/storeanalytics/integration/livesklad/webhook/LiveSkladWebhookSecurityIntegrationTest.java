package com.storeanalytics.integration.livesklad.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class LiveSkladWebhookSecurityIntegrationTest {

    private static final String SALE_SECRET =
            "sale_return_secret_12345678901234567890";
    private static final String SALE_PREVIOUS_SECRET =
            "previous_sale_secret_123456789012345";
    private static final String ORDER_SECRET =
            "order_return_secret_123456789012345678";
    private static final String ORDER_PREVIOUS_SECRET =
            "previous_order_secret_12345678901234";
    private static final String SALE_PATH =
            "/api/integrations/livesklad/webhooks/sale-returns";
    private static final String ORDER_PATH =
            "/api/integrations/livesklad/webhooks/order-returns";

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.livesklad.webhook.enabled", () -> "true");
        registry.add("app.livesklad.webhook.sale-return-secret", () -> SALE_SECRET);
        registry.add(
                "app.livesklad.webhook.sale-return-previous-secret",
                () -> SALE_PREVIOUS_SECRET
        );
        registry.add("app.livesklad.webhook.order-return-secret", () -> ORDER_SECRET);
        registry.add(
                "app.livesklad.webhook.order-return-previous-secret",
                () -> ORDER_PREVIOUS_SECRET
        );
        registry.add("app.livesklad.webhook.max-body-bytes", () -> "1024");
    }

    @Test
    void verifiesBothUrlsWithoutCreatingSessionOrCsrfState() throws Exception {
        int receiptsBefore = receiptCount();
        verify(SALE_PATH, SALE_SECRET, "sale-verification-123");
        verify(ORDER_PATH, ORDER_SECRET, "order-verification-456");

        assertThat(receiptCount()).isEqualTo(receiptsBefore);
    }

    @Test
    void acceptsAndDeduplicatesSaleReturnEvent() throws Exception {
        String body = event(
                "sale-return-event-1",
                "sale-return-action",
                "return-sale",
                "Возврат продажи",
                "return-document-1",
                15030
        );

        postEvent(SALE_PATH, SALE_SECRET, body)
                .andExpect(status().isOk())
                .andExpect(content().string("OK"));
        postEvent(SALE_PATH, SALE_SECRET, body)
                .andExpect(status().isOk())
                .andExpect(content().string("OK"));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                """
                SELECT
                    webhook_kind,
                    event_id,
                    action_id,
                    action_group_id,
                    action_name,
                    payload -> 'data' ->> 'id' AS entity_id,
                    delivery_count,
                    payload_mismatch
                FROM livesklad_webhook_receipts
                WHERE webhook_kind = 'SALE_RETURN'
                  AND event_id = 'sale-return-event-1'
                """
        );
        assertThat(row)
                .containsEntry("webhook_kind", "SALE_RETURN")
                .containsEntry("event_id", "sale-return-event-1")
                .containsEntry("action_id", "sale-return-action")
                .containsEntry("action_group_id", "return-sale")
                .containsEntry("action_name", "Возврат продажи")
                .containsEntry("entity_id", "return-document-1")
                .containsEntry("delivery_count", 2)
                .containsEntry("payload_mismatch", false);
    }

    @Test
    void acceptsOrderReturnOnlyWithItsOwnCurrentOrPreviousSecret()
            throws Exception {
        String currentBody = event(
                "order-return-event-1",
                "order-return-action",
                "return-order",
                "Возврат по заказу",
                "order-1",
                8500
        );

        postEvent(ORDER_PATH, SALE_SECRET, currentBody)
                .andExpect(status().isUnauthorized());
        postEvent(ORDER_PATH, ORDER_SECRET, currentBody)
                .andExpect(status().isOk())
                .andExpect(content().string("OK"));

        String previousBody = event(
                "order-return-event-2",
                "order-return-action",
                "return-order",
                "Возврат по заказу",
                "order-2",
                1200
        );
        postEvent(ORDER_PATH, ORDER_PREVIOUS_SECRET, previousBody)
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM livesklad_webhook_receipts
                WHERE webhook_kind = 'ORDER_RETURN'
                  AND event_id IN ('order-return-event-1', 'order-return-event-2')
                """,
                Integer.class
        )).isEqualTo(2);
    }

    @Test
    void preservesFirstPayloadAndFlagsChangedDuplicate() throws Exception {
        postEvent(
                SALE_PATH,
                SALE_PREVIOUS_SECRET,
                event(
                        "sale-return-event-mismatch",
                        "sale-return-action",
                        "return-sale",
                        "Возврат продажи",
                        "return-document-2",
                        1000
                )
        ).andExpect(status().isOk());

        postEvent(
                SALE_PATH,
                SALE_SECRET,
                event(
                        "sale-return-event-mismatch",
                        "sale-return-action",
                        "return-sale",
                        "Возврат продажи",
                        "return-document-2",
                        1100
                )
        ).andExpect(status().isOk());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                """
                SELECT
                    payload -> 'data' ->> 'sum' AS first_sum,
                    delivery_count,
                    payload_mismatch,
                    payload_sha256 <> last_payload_sha256 AS hashes_differ
                FROM livesklad_webhook_receipts
                WHERE webhook_kind = 'SALE_RETURN'
                  AND event_id = 'sale-return-event-mismatch'
                """
        );
        assertThat(row)
                .containsEntry("first_sum", "1000")
                .containsEntry("delivery_count", 2)
                .containsEntry("payload_mismatch", true)
                .containsEntry("hashes_differ", true);
    }

    @Test
    void rejectsUnauthenticatedMalformedAndOversizedRequests() throws Exception {
        postEvent(SALE_PATH, "wrong_secret_value_123456789012345", event(
                "sale-return-event-rejected",
                "sale-return-action",
                "return-sale",
                "Возврат продажи",
                "return-document-rejected",
                100
        )).andExpect(status().isUnauthorized());

        postEvent(SALE_PATH, SALE_SECRET, "{\"data\":{}}")
                .andExpect(status().isBadRequest())
                .andExpect(content().string("INVALID"));

        mockMvc.perform(post(SALE_PATH)
                        .contentType(MediaType.TEXT_PLAIN)
                        .header(
                                LiveSkladWebhookAuthenticationFilter.SECRET_HEADER,
                                SALE_SECRET
                        )
                        .content("{}"))
                .andExpect(status().isUnsupportedMediaType());

        mockMvc.perform(post(SALE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(
                                LiveSkladWebhookAuthenticationFilter.SECRET_HEADER,
                                SALE_SECRET
                        )
                        .content("x".repeat(1025)))
                .andExpect(status().isPayloadTooLarge());

        mockMvc.perform(get(SALE_PATH)
                        .header(
                                LiveSkladWebhookAuthenticationFilter.SECRET_HEADER,
                                SALE_SECRET
                        ))
                .andExpect(status().isMethodNotAllowed());

        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM livesklad_webhook_receipts
                WHERE event_id = 'sale-return-event-rejected'
                """,
                Integer.class
        )).isZero();
    }

    private void verify(String path, String secret, String verification)
            throws Exception {
        mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(
                                LiveSkladWebhookAuthenticationFilter.SECRET_HEADER,
                                secret
                        )
                        .header(
                                LiveSkladWebhookService.VERIFICATION_HEADER,
                                verification
                        )
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string(verification));
    }

    private org.springframework.test.web.servlet.ResultActions postEvent(
            String path,
            String secret,
            String body
    ) throws Exception {
        return mockMvc.perform(post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .header(
                        LiveSkladWebhookAuthenticationFilter.SECRET_HEADER,
                        secret
                )
                .content(body));
    }

    private int receiptCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM livesklad_webhook_receipts",
                Integer.class
        );
    }

    private String event(
            String eventId,
            String actionId,
            String groupId,
            String actionName,
            String entityId,
            int amount
    ) {
        return """
                {
                  "eventId": "%s",
                  "action": {
                    "id": "%s",
                    "groupId": "%s",
                    "name": "%s"
                  },
                  "data": {
                    "id": "%s",
                    "sum": %d
                  }
                }
                """.formatted(
                eventId,
                actionId,
                groupId,
                actionName,
                entityId,
                amount
        );
    }
}
