package com.storeanalytics.notification.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.model.UserRole;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.notification.delivery.TelegramDeliveryOperationalState;
import com.storeanalytics.notification.delivery.TelegramDeliveryOperationalStateStore;
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
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class TelegramDeliveryOperationsIntegrationTest {

    private static final String PASSWORD = "operations correct horse battery staple";

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

    @Autowired
    private TelegramDeliveryOperationalStateStore operationalStateStore;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void administratorSeesSanitizedQueueFactsAndPrioritizedIncidents() throws Exception {
        AppUser administrator = createUser("ops-admin@example.test", UserRole.ADMIN);
        AppUser recipient = createUser("recipient@example.test", UserRole.MANAGER);
        Store store = createStore();
        Instant now = Instant.now();
        UUID subscriptionId = activeSubscription(recipient.getId(), now);
        blockedSubscription(createUser("blocked@example.test", UserRole.MANAGER).getId(), now);

        delivery(store.getId(), recipient.getId(), subscriptionId, new DeliverySpec(
                "PENDING", null, null,
                now.minusSeconds(120), now.minusSeconds(120), null
        ));
        delivery(store.getId(), recipient.getId(), subscriptionId, new DeliverySpec(
                "WAITING_RETRY", "AUTHENTICATION", "Safe authentication failure",
                now.minusSeconds(90), now.minusSeconds(60), null
        ));
        delivery(store.getId(), recipient.getId(), subscriptionId, new DeliverySpec(
                "RUNNING", null, null,
                now.minusSeconds(80), now.minusSeconds(80), now.minusSeconds(30)
        ));
        delivery(store.getId(), recipient.getId(), subscriptionId, new DeliverySpec(
                "PERMANENT_FAILED", "INVALID_REQUEST", "Safe permanent failure",
                now.minusSeconds(70), now.minusSeconds(70), null
        ));
        UUID unknownId = delivery(
                store.getId(), recipient.getId(), subscriptionId, new DeliverySpec(
                        "UNKNOWN_OUTCOME", "LEASE_EXPIRED_AFTER_ATTEMPT",
                        "Safe unknown outcome", now.minusSeconds(60),
                        now.minusSeconds(60), null
                )
        );


        TelegramDeliveryOperationalState operationalState =
                operationalStateStore.load(now);
        assertThat(operationalState.readyPending()).isEqualTo(1);
        assertThat(operationalState.readyRetry()).isEqualTo(1);
        assertThat(operationalState.authenticationRetry()).isEqualTo(1);
        assertThat(operationalState.running()).isEqualTo(1);
        assertThat(operationalState.expiredLease()).isEqualTo(1);
        assertThat(operationalState.permanentFailed()).isEqualTo(1);
        assertThat(operationalState.unknownOutcome()).isEqualTo(1);
        assertThat(operationalState.blockedSubscription()).isEqualTo(1);
        MockHttpSession session = session(login(administrator.getEmail()));

        mockMvc.perform(get("/api/admin/notifications/telegram/deliveries")
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.attentionLevel").value("CRITICAL"))
                .andExpect(jsonPath("$.summary.readyPending").value(1))
                .andExpect(jsonPath("$.summary.readyRetries").value(1))
                .andExpect(jsonPath("$.summary.running").value(1))
                .andExpect(jsonPath("$.summary.overdueRunning").value(1))
                .andExpect(jsonPath("$.summary.permanentFailed").value(1))
                .andExpect(jsonPath("$.summary.unknownOutcome").value(1))
                .andExpect(jsonPath("$.summary.activeSubscriptions").value(1))
                .andExpect(jsonPath("$.summary.blockedSubscriptions").value(1))
                .andExpect(jsonPath("$.incidents[0].deliveryId").value(unknownId.toString()))
                .andExpect(jsonPath("$.incidents[0].status").value("UNKNOWN_OUTCOME"))
                .andExpect(jsonPath("$.incidents[0].errorSummary")
                        .value("Safe unknown outcome"))
                .andExpect(jsonPath("$.incidents[0].telegramChatId").doesNotExist())
                .andExpect(jsonPath("$.incidents[0].renderedText").doesNotExist());
    }

    @Test
    void deliveryOperationsEndpointIsRestrictedToAdministrators() throws Exception {
        AppUser manager = createUser("ops-manager@example.test", UserRole.MANAGER);
        MockHttpSession managerSession = session(login(manager.getEmail()));

        mockMvc.perform(get("/api/admin/notifications/telegram/deliveries"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/notifications/telegram/deliveries")
                        .session(managerSession))
                .andExpect(status().isForbidden());
    }

    private AppUser createUser(String email, UserRole role) {
        AppUser user = new AppUser(email, passwordEncoder.encode(PASSWORD), email, role);
        user.changePassword(passwordEncoder.encode(PASSWORD));
        return userRepository.saveAndFlush(user);
    }

    private Store createStore() {
        String externalId = "ops-store-" + UUID.randomUUID();
        return storeRepository.saveAndFlush(Store.manual(
                externalId,
                "Операционный магазин",
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

    private void blockedSubscription(UUID userId, Instant now) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO telegram_subscriptions (
                    id, user_id, bot_code, telegram_user_id, telegram_chat_id,
                    status, quiet_hours_enabled, blocked_at
                ) VALUES (?, ?, 'primary', ?, ?, 'BOT_BLOCKED', false, ?)
                """,
                id, userId, positive(id.getMostSignificantBits()),
                positive(id.getLeastSignificantBits()), Timestamp.from(now)
        );
    }

    private record DeliverySpec(
            String status, String errorCode, String errorSummary,
            Instant createdAt, Instant nextAttemptAt, Instant leaseUntil
    ) {
    }

    private UUID delivery(
            UUID storeId,
            UUID recipientId,
            UUID subscriptionId,
            DeliverySpec spec
    ) {
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO notification_events (
                    id, store_id, event_type, audience, deduplication_key,
                    notification_policy_version, priority, event_payload,
                    payload_hash, not_before, expires_at, created_at
                ) VALUES (?, ?, 'DAILY_STORE_PULSE', 'MANAGER', ?, 'test-v1',
                          'NORMAL', '{}'::jsonb, ?, ?, ?, ?)
                """,
                eventId, storeId, "operations:" + eventId, "1".repeat(64),
                Timestamp.from(spec.createdAt()),
                Timestamp.from(spec.createdAt().plusSeconds(3600)),
                Timestamp.from(spec.createdAt())
        );
        UUID deliveryId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO notification_deliveries (
                    id, event_id, recipient_user_id, subscription_id, status,
                    render_version, rendered_text, content_hash,
                    scheduled_at, next_attempt_at, expires_at,
                    attempt_count, max_attempts, lease_owner, lease_until,
                    error_code, error_summary, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 'test-v1', 'Safe text', ?, ?, ?, ?,
                          1, 5, ?, ?, ?, ?, ?, ?)
                """,
                deliveryId, eventId, recipientId, subscriptionId, spec.status(),
                "1".repeat(64), Timestamp.from(spec.createdAt()),
                Timestamp.from(spec.nextAttemptAt()),
                Timestamp.from(spec.createdAt().plusSeconds(3600)),
                "RUNNING".equals(spec.status()) ? "test-worker" : null,
                spec.leaseUntil() == null ? null : Timestamp.from(spec.leaseUntil()),
                spec.errorCode(), spec.errorSummary(),
                Timestamp.from(spec.createdAt()), Timestamp.from(spec.createdAt())
        );
        return deliveryId;
    }

    private org.springframework.test.web.servlet.ResultActions login(String email)
            throws Exception {
        Cookie csrfCookie = csrfCookie();
        return mockMvc.perform(post("/api/auth/login")
                .cookie(csrfCookie)
                .header("X-XSRF-TOKEN", csrfCookie.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s"}
                        """.formatted(email, PASSWORD)));
    }

    private Cookie csrfCookie() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    private MockHttpSession session(org.springframework.test.web.servlet.ResultActions login)
            throws Exception {
        return (MockHttpSession) login.andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
    }

    private long positive(long value) {
        return value == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(value);
    }
}
