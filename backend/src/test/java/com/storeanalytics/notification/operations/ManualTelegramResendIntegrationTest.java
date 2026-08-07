package com.storeanalytics.notification.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.model.UserRole;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.model.StoreSchedule;
import com.storeanalytics.store.repository.StoreRepository;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class ManualTelegramResendIntegrationTest {

    private static final String PASSWORD = "resend correct horse battery staple";

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void administratorCreatesOneAuditedResendForIdempotentRequest() throws Exception {
        AppUser administrator = createUser("resend-admin@example.test", UserRole.ADMIN);
        AppUser recipient = createUser("resend-recipient@example.test", UserRole.MANAGER);
        Store store = createStore();
        Instant now = Instant.now();
        UUID subscriptionId = activeSubscription(recipient.getId(), now);
        UUID sourceId = delivery(
                store.getId(), recipient.getId(), subscriptionId,
                "UNKNOWN_OUTCOME", now.minusSeconds(60), now.plusSeconds(3_600)
        );
        SessionCsrf auth = login(administrator.getEmail());
        String body = """
                {
                  "reason": "Проверена история попытки, риск дубля принят",
                  "acknowledgeDuplicateRisk": true
                }
                """;

        resend(auth, sourceId, "telegram-resend-test-0001", body)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.sourceDeliveryId").value(sourceId.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));

        UUID resendId = jdbcTemplate.queryForObject(
                "SELECT id FROM notification_deliveries WHERE manual_resend_of = ?",
                UUID.class,
                sourceId
        );
        assertThat(resendId).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM notification_deliveries resend
                JOIN notification_deliveries source ON source.id = resend.manual_resend_of
                WHERE resend.id = ?
                  AND resend.status = 'PENDING'
                  AND resend.attempt_count = 0
                  AND resend.event_id = source.event_id
                  AND resend.recipient_user_id = source.recipient_user_id
                  AND resend.subscription_id = source.subscription_id
                  AND resend.rendered_text = source.rendered_text
                  AND resend.content_hash = source.content_hash
                  AND resend.expires_at = source.expires_at
                  AND resend.requested_by = ?
                """,
                Long.class,
                resendId,
                administrator.getId()
        )).isEqualTo(1L);

        resend(auth, sourceId, "telegram-resend-test-0001", body)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.deliveryId").value(resendId.toString()));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_deliveries WHERE manual_resend_of = ?",
                Long.class,
                sourceId
        )).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = ? AND entity_id = ?",
                Long.class,
                "TELEGRAM_DELIVERY_RESEND_REQUESTED",
                resendId.toString()
        )).isEqualTo(1L);

        resend(auth, sourceId, "telegram-resend-test-0002", body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TELEGRAM_DELIVERY_RESEND_CONFLICT"));
    }

    @Test
    void resendRejectsMissingRiskAcceptanceAndIneligibleOrExpiredSource() throws Exception {
        AppUser administrator = createUser("resend-guard-admin@example.test", UserRole.ADMIN);
        AppUser recipient = createUser("resend-guard-recipient@example.test", UserRole.MANAGER);
        Store store = createStore();
        Instant now = Instant.now();
        UUID subscriptionId = activeSubscription(recipient.getId(), now);
        UUID pendingId = delivery(
                store.getId(), recipient.getId(), subscriptionId,
                "PENDING", now, now.plusSeconds(3_600)
        );
        UUID expiredId = delivery(
                store.getId(), recipient.getId(), subscriptionId,
                "UNKNOWN_OUTCOME", now.minusSeconds(4_000), now.minusSeconds(400)
        );
        SessionCsrf auth = login(administrator.getEmail());

        resend(auth, pendingId, "guard-resend-0001", requestBody(false))
                .andExpect(status().isBadRequest());
        resend(auth, pendingId, "guard-resend-0002", requestBody(true))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TELEGRAM_DELIVERY_RESEND_CONFLICT"));
        resend(auth, expiredId, "guard-resend-0003", requestBody(true))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TELEGRAM_DELIVERY_RESEND_CONFLICT"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_deliveries WHERE manual_resend_of IS NOT NULL",
                Long.class
        )).isZero();
    }

    @Test
    void managerCannotRequestManualResend() throws Exception {
        AppUser manager = createUser("resend-manager@example.test", UserRole.MANAGER);
        SessionCsrf auth = login(manager.getEmail());

        resend(auth, UUID.randomUUID(), "manager-resend-0001", requestBody(true))
                .andExpect(status().isForbidden());
    }

    private ResultActions resend(
            SessionCsrf auth,
            UUID deliveryId,
            String idempotencyKey,
            String body
    ) throws Exception {
        return mockMvc.perform(post(
                        "/api/admin/notifications/telegram/deliveries/{deliveryId}/resend",
                        deliveryId
                )
                .session(auth.session())
                .cookie(auth.csrfCookie())
                .header("X-XSRF-TOKEN", auth.csrfCookie().getValue())
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String requestBody(boolean acknowledgeDuplicateRisk) {
        return """
                {
                  "reason": "Проверена история попытки доставки",
                  "acknowledgeDuplicateRisk": %s
                }
                """.formatted(acknowledgeDuplicateRisk);
    }

    private AppUser createUser(String email, UserRole role) {
        AppUser user = new AppUser(email, passwordEncoder.encode(PASSWORD), email, role);
        user.changePassword(passwordEncoder.encode(PASSWORD));
        return userRepository.saveAndFlush(user);
    }

    private Store createStore() {
        String externalId = "resend-store-" + UUID.randomUUID();
        return storeRepository.saveAndFlush(Store.manual(
                externalId,
                "Магазин ручной отправки",
                null,
                new StoreSchedule(
                        "Europe/Kaliningrad",
                        LocalTime.MIDNIGHT,
                        LocalTime.of(10, 0),
                        LocalTime.of(21, 0)
                )
        ));
    }

    private UUID activeSubscription(UUID userId, Instant now) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO telegram_subscriptions (
                    id, user_id, bot_code, telegram_user_id, telegram_chat_id,
                    status, quiet_hours_enabled, confirmed_at
                ) VALUES (?, ?, 'primary', ?, ?, 'ACTIVE', false, ?)
                """,
                id, userId, positive(id.getMostSignificantBits()),
                positive(id.getLeastSignificantBits()), Timestamp.from(now)
        );
        return id;
    }

    private UUID delivery(
            UUID storeId,
            UUID recipientId,
            UUID subscriptionId,
            String status,
            Instant scheduledAt,
            Instant expiresAt
    ) {
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO notification_events (
                    id, store_id, event_type, audience, deduplication_key,
                    notification_policy_version, priority, event_payload,
                    payload_hash, not_before, expires_at
                ) VALUES (?, ?, 'DAILY_STORE_PULSE', 'MANAGER', ?, 'test-v1',
                          'NORMAL', '{}'::jsonb, ?, ?, ?)
                """,
                eventId, storeId, "manual-resend:" + eventId, "1".repeat(64),
                Timestamp.from(scheduledAt), Timestamp.from(expiresAt)
        );
        UUID deliveryId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO notification_deliveries (
                    id, event_id, recipient_user_id, subscription_id, status,
                    render_version, rendered_text, content_hash,
                    scheduled_at, next_attempt_at, expires_at,
                    attempt_count, max_attempts, error_code, error_summary
                ) VALUES (?, ?, ?, ?, ?, 'test-v1', 'Safe text', ?, ?, ?, ?,
                          1, 5, ?, 'Safe terminal summary')
                """,
                deliveryId, eventId, recipientId, subscriptionId, status,
                "1".repeat(64), Timestamp.from(scheduledAt), Timestamp.from(scheduledAt),
                Timestamp.from(expiresAt),
                "UNKNOWN_OUTCOME".equals(status) ? "UNKNOWN_OUTCOME" : null
        );
        return deliveryId;
    }

    private SessionCsrf login(String email) throws Exception {
        Cookie loginCsrf = csrfCookie(null);
        MockHttpSession session = (MockHttpSession) mockMvc.perform(post("/api/auth/login")
                        .cookie(loginCsrf)
                        .header("X-XSRF-TOKEN", loginCsrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
        return new SessionCsrf(session, csrfCookie(session));
    }

    private Cookie csrfCookie(MockHttpSession session) throws Exception {
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request =
                get("/api/auth/csrf");
        if (session != null) {
            request.session(session);
        }
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn();
        Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    private long positive(long value) {
        return value == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(value);
    }

    private record SessionCsrf(MockHttpSession session, Cookie csrfCookie) {
    }
}
