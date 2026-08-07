package com.storeanalytics.integration.telegram.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
class TelegramWebhookSecurityIntegrationTest {

    private static final String SECRET = "test_webhook_secret_123456789";
    private static final String PREVIOUS_SECRET = "previous_webhook_secret_987654321";

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
        registry.add("app.notification.telegram.enabled", () -> "true");
        registry.add("app.notification.telegram.webhook-enabled", () -> "true");
        registry.add("app.notification.telegram.bot-code", () -> "primary");
        registry.add("app.notification.telegram.webhook-secret", () -> SECRET);
        registry.add("app.notification.telegram.webhook-previous-secret",
                () -> PREVIOUS_SECRET);
        registry.add("app.notification.telegram.webhook-max-body-bytes", () -> "1024");
    }

    @Test
    void rejectsUnknownBotBeforeController() throws Exception {
        mockMvc.perform(post("/api/integrations/telegram/unknown/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(
                                TelegramWebhookAuthenticationFilter.SECRET_HEADER,
                                SECRET
                        )
                        .content("{\"update_id\":7001}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsWrongSecretAndUnsupportedContentType() throws Exception {
        mockMvc.perform(post("/api/integrations/telegram/primary/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(
                                TelegramWebhookAuthenticationFilter.SECRET_HEADER,
                                "wrong_secret_value"
                        )
                        .content("{\"update_id\":7002}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/integrations/telegram/primary/webhook")
                        .contentType(MediaType.TEXT_PLAIN)
                        .header(
                                TelegramWebhookAuthenticationFilter.SECRET_HEADER,
                                SECRET
                        )
                        .content("{\"update_id\":7003}"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void acceptsPreviousSecretDuringControlledRotation() throws Exception {
        mockMvc.perform(post("/api/integrations/telegram/primary/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(
                                TelegramWebhookAuthenticationFilter.SECRET_HEADER,
                                PREVIOUS_SECRET
                        )
                        .content("{\"update_id\":7006}"))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM telegram_update_receipts "
                        + "WHERE bot_code = 'primary' AND update_id = 7006",
                Integer.class
        )).isOne();
    }

    @Test
    void rejectsOversizedBodyBeforeJsonMapping() throws Exception {
        mockMvc.perform(post("/api/integrations/telegram/primary/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(
                                TelegramWebhookAuthenticationFilter.SECRET_HEADER,
                                SECRET
                        )
                        .content("x".repeat(1025)))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void mapsMyChatMemberEnvelopeWithoutTrustingAUserMessage() throws Exception {
        String body = """
                {
                  "update_id": 7005,
                  "my_chat_member": {
                    "chat": {"id": 99887766, "type": "private"},
                    "old_chat_member": {"status": "kicked"},
                    "new_chat_member": {"status": "member"}
                  }
                }
                """;

        mockMvc.perform(post("/api/integrations/telegram/primary/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(
                                TelegramWebhookAuthenticationFilter.SECRET_HEADER,
                                SECRET
                        )
                        .content(body))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT update_type FROM telegram_update_receipts "
                        + "WHERE bot_code = 'primary' AND update_id = 7005",
                String.class
        )).isEqualTo("MY_CHAT_MEMBER");
    }

    @Test
    void acceptsAndDeduplicatesSupportedEnvelopeWithoutSessionOrCsrf()
            throws Exception {
        String body = "{\"update_id\":7004}";

        mockMvc.perform(post("/api/integrations/telegram/primary/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(
                                TelegramWebhookAuthenticationFilter.SECRET_HEADER,
                                SECRET
                        )
                        .content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/integrations/telegram/primary/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(
                                TelegramWebhookAuthenticationFilter.SECRET_HEADER,
                                SECRET
                        )
                        .content(body))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM telegram_update_receipts "
                        + "WHERE bot_code = 'primary' AND update_id = 7004",
                Integer.class
        )).isOne();
    }
}
