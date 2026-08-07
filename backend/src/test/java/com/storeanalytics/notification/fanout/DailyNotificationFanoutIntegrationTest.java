package com.storeanalytics.notification.fanout;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.interpretation.contract.CanonicalLlmJson;
import com.storeanalytics.interpretation.contract.LlmCanonicalJsonCodec;
import com.storeanalytics.notification.daily.DailyStorePulsePayload;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
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

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class DailyNotificationFanoutIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private DailyNotificationEventFanoutService service;

    @Autowired
    private LlmCanonicalJsonCodec jsonCodec;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void createsOneDailyDeliveryAndReceiptWithoutAnLlmJob() throws Exception {
        Instant now = Instant.now();
        UUID storeId = insertStore();
        insertRecipient(storeId, now);
        UUID eventId = insertDailyEvent(storeId, now);

        NotificationFanoutResult result = service.processNext().orElseThrow();

        assertThat(result.eventId()).isEqualTo(eventId);
        assertThat(result.outcome()).isEqualTo(
                NotificationFanoutOutcome.DELIVERIES_CREATED
        );
        assertThat(result.deliveryCount()).isOne();
        assertThat(service.processNext()).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT rendered_text FROM notification_deliveries WHERE event_id = ?",
                String.class,
                eventId
        )).contains(
                "☀️ УТРО · СВОДКА",
                "Магазин daily",
                "Доп. продажи",
                "Чехлы",
                "Анна"
        );
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM llm_analysis_jobs",
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_event_fanout_receipts "
                        + "WHERE event_id = ?",
                Integer.class,
                eventId
        )).isOne();
    }

    private UUID insertStore() {
        UUID connectionId = jdbcTemplate.queryForObject(
                "SELECT id FROM integration_connections "
                        + "WHERE connection_key = 'livesklad-default'",
                UUID.class
        );
        UUID storeId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO stores (id, connection_id, source_system, external_id, "
                        + "name, timezone) VALUES (?, ?, 'LIVESKLAD', ?, ?, ?)",
                storeId,
                connectionId,
                "daily-" + storeId,
                "Магазин daily",
                "Europe/Moscow"
        );
        return storeId;
    }

    private void insertRecipient(UUID storeId, Instant now) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO app_users (id, email, password_hash, display_name, role) "
                        + "VALUES (?, ?, '{noop}test', 'Manager', 'MANAGER')",
                userId,
                "daily-" + userId + "@example.test"
        );
        jdbcTemplate.update(
                "INSERT INTO user_store_access (user_id, store_id) VALUES (?, ?)",
                userId,
                storeId
        );
        jdbcTemplate.update(
                """
                INSERT INTO telegram_subscriptions (
                    user_id, bot_code, telegram_user_id, telegram_chat_id,
                    status, quiet_hours_enabled, confirmed_at
                ) VALUES (?, 'store-analytics-primary', ?, ?, 'ACTIVE', false, ?)
                """,
                userId,
                Math.abs(userId.getMostSignificantBits()),
                Math.abs(userId.getLeastSignificantBits()),
                Timestamp.from(now)
        );
    }

    private UUID insertDailyEvent(UUID storeId, Instant now) throws Exception {
        DailyStorePulsePayload payload = new DailyStorePulsePayload(
                1,
                LocalDate.parse("2026-08-02"),
                LocalDate.parse("2026-08-01"),
                metric("125000.00", "8.5"),
                metric("12500.00", "2.0"),
                metric("26000.00", "11.0"),
                metric("5200.00", "4.0"),
                List.of(named("ACCESSORY", "Чехлы", "18000.00", "12.0")),
                List.of(named(UUID.randomUUID().toString(), "Анна", "70000.00", "9.0")),
                new DailyStorePulsePayload.Quality(true, 0)
        );
        CanonicalLlmJson canonical = jsonCodec.canonicalize(
                objectMapper.writeValueAsString(payload)
        );
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO notification_events (
                    id, store_id, event_type, audience, deduplication_key,
                    notification_policy_version, priority, event_payload,
                    payload_hash, not_before, expires_at
                ) VALUES (
                    ?, ?, 'DAILY_STORE_PULSE', 'MANAGER', ?,
                    'daily-store-pulse-v1', 'NORMAL', CAST(? AS jsonb), ?, ?, ?
                )
                """,
                eventId,
                storeId,
                "daily-fanout-test:" + eventId,
                canonical.canonicalJson(),
                canonical.contentHash(),
                Timestamp.from(now.minusSeconds(1)),
                Timestamp.from(now.plusSeconds(3600))
        );
        return eventId;
    }

    private DailyStorePulsePayload.Metric metric(String value, String change) {
        return new DailyStorePulsePayload.Metric(
                new BigDecimal(value), new BigDecimal(change)
        );
    }

    private DailyStorePulsePayload.NamedMetric named(
            String code,
            String name,
            String value,
            String change
    ) {
        return new DailyStorePulsePayload.NamedMetric(
                code, name, new BigDecimal(value), new BigDecimal(change)
        );
    }
}
