package com.storeanalytics.interpretation.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storeanalytics.interpretation.contract.CanonicalLlmJson;
import com.storeanalytics.interpretation.contract.LlmCanonicalJsonCodec;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Comparison;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.EmployeeFacts;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.EvidenceIndexEntry;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Fact;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Facts;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Manifest;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Materiality;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.QualityStatus;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Scope;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Sufficiency;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Unit;
import com.storeanalytics.interpretation.query.WeeklyInsightQueryService;
import com.storeanalytics.interpretation.web.WeeklyInsightController;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import com.storeanalytics.notification.fanout.NotificationEventFanoutService;
import com.storeanalytics.notification.fanout.NotificationFanoutOutcome;
import com.storeanalytics.notification.fanout.NotificationFanoutResult;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@Import(WeeklyV2ReadAndFanoutIntegrationTest.FixedClockConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class WeeklyV2ReadAndFanoutIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-07T12:00:00Z");
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 7, 27);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 8, 2);

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private WeeklySnapshotStore snapshotStore;

    @Autowired
    private WeeklySnapshotPayloadCodec snapshotCodec;

    @Autowired
    private LlmCanonicalJsonCodec llmJsonCodec;

    @Autowired
    private WeeklyInsightQueryService queryService;

    @Autowired
    private NotificationEventFanoutService fanoutService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void readsPersistedV2ThroughApiAndCreatesOnePendingTelegramDelivery()
            throws Exception {
        Fixture fixture = fixture();
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new WeeklyInsightController(queryService))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(
                        JsonMapper.builder().findAndAddModules().build()
                ))
                .build();

        String apiBody = mockMvc.perform(get(
                        "/api/stores/{storeId}/insights/weekly/current",
                        fixture.storeId()
                ))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL, "private, no-store"
                ))
                .andExpect(jsonPath("$.state").value("READY"))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.content.store.headline.text")
                        .value("Магазин улучшил общий результат, однако структура продаж требует внимания."))
                .andExpect(jsonPath(
                        "$.content.teamInsights.competencyLeaders[0].employeeNames[0]"
                ).value("Анна"))
                .andExpect(jsonPath("$.content.employees[0].employeeId")
                        .value(fixture.employeeId().toString()))
                .andExpect(jsonPath("$.content.employees[0].displayName")
                        .value("Анна"))
                .andExpect(jsonPath("$.content.employees[0].insight.employeeRef")
                        .doesNotExist())
                .andExpect(jsonPath(
                        "$.content.employees[0].insight.categoryPerformance.summary.text"
                ).value("Сервисное направление является подтверждённой сильной стороной сотрудника."))
                .andExpect(jsonPath("$.content.evidence.length()").value(7))
                .andExpect(jsonPath("$.content.evidence[0].evidenceCode").value("EV001"))
                .andExpect(jsonPath("$.content.evidence[0].employeeId")
                        .value(fixture.employeeId().toString()))
                .andExpect(jsonPath("$.content.evidence[0].formattedValue").value("18%"))
                .andExpect(jsonPath(
                        "$.content.store.headline.evidenceRefs[0]"
                ).value("EV006"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(apiBody).doesNotContain(
                "STORE.NET_REVENUE", "EMP:E01", "\"E01\""
        );

        NotificationFanoutResult result = fanoutService.processNext().orElseThrow();

        assertThat(result.eventId()).isEqualTo(fixture.eventId());
        assertThat(result.outcome()).isEqualTo(
                NotificationFanoutOutcome.DELIVERIES_CREATED
        );
        assertThat(result.deliveryCount()).isOne();
        assertThat(fanoutService.processNext()).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT content_hash FROM llm_interpretations WHERE id = ?",
                String.class,
                fixture.interpretationId()
        )).isEqualTo(fixture.contentHash());

        Map<String, Object> delivery = jdbcTemplate.queryForMap(
                "SELECT status, attempt_count, provider_message_id, sent_at, "
                        + "rendered_text, content_hash "
                        + "FROM notification_deliveries WHERE event_id = ?",
                fixture.eventId()
        );
        assertThat(delivery.get("status")).isEqualTo("PENDING");
        assertThat(delivery.get("attempt_count")).isEqualTo(0);
        assertThat(delivery.get("provider_message_id")).isNull();
        assertThat(delivery.get("sent_at")).isNull();
        assertThat(delivery.get("content_hash").toString())
                .matches("[a-f0-9]{64}");
        assertThat(delivery.get("rendered_text").toString())
                .contains(
                        "НЕДЕЛЯ · ОТЧЁТ ГОТОВ",
                        "версия 1",
                        "Анна",
                        "КОМАНДА",
                        "Распространить сильную практику"
                )
                .doesNotContain("E01", "SERVICE_SALES", "evidenceRefs");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_deliveries WHERE event_id = ?",
                Integer.class,
                fixture.eventId()
        )).isOne();
    }

    private Fixture fixture() throws IOException {
        UUID connectionId = jdbcTemplate.queryForObject(
                "SELECT id FROM integration_connections "
                        + "WHERE connection_key = 'livesklad-default'",
                UUID.class
        );
        UUID storeId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        insertStoreAndEmployee(connectionId, storeId, employeeId);
        UUID syncJobId = insertSyncJob(connectionId);
        PersistedWeeklySnapshot snapshot = persistSnapshot(
                storeId, employeeId, syncJobId
        );
        CanonicalLlmJson content = llmJsonCodec.canonicalize(new String(
                getClass().getResourceAsStream(
                        "/contracts/llm/examples/"
                                + "weekly-interpretation-content-v2-ready.json"
                ).readAllBytes(),
                StandardCharsets.UTF_8
        ));
        PublishedInterpretation published = insertPublishedInterpretation(
                storeId, snapshot.id(), content
        );
        UUID eventId = insertEvent(
                storeId, snapshot.id(), published.interpretationId()
        );
        insertRecipient(storeId);
        return new Fixture(
                storeId,
                employeeId,
                eventId,
                published.interpretationId(),
                content.contentHash()
        );
    }

    private void insertStoreAndEmployee(
            UUID connectionId,
            UUID storeId,
            UUID employeeId
    ) {
        jdbcTemplate.update(
                "INSERT INTO stores (id, connection_id, source_system, external_id, "
                        + "name, timezone) VALUES (?, ?, 'LIVESKLAD', ?, ?, ?)",
                storeId,
                connectionId,
                "v2-acceptance-" + storeId,
                "Магазин v2 acceptance",
                "Europe/Moscow"
        );
        jdbcTemplate.update(
                "INSERT INTO employees (id, connection_id, source_system, external_id, "
                        + "full_name) VALUES (?, ?, 'LIVESKLAD', ?, 'Анна')",
                employeeId,
                connectionId,
                "v2-employee-" + employeeId
        );
        jdbcTemplate.update(
                "INSERT INTO employee_store_assignments (employee_id, store_id) "
                        + "VALUES (?, ?)",
                employeeId,
                storeId
        );
    }

    private UUID insertSyncJob(UUID connectionId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO sync_jobs (
                    id, connection_id, job_type, status, phase,
                    period_start, period_end, cursor_start, current_window_end,
                    window_size_minutes, max_attempts, next_attempt_at,
                    started_at, finished_at
                ) VALUES (
                    ?, ?, 'BACKFILL', 'SUCCESS', 'RETURNS', ?, ?, ?, ?,
                    1440, 3, ?, ?, ?
                )
                """,
                id,
                connectionId,
                Timestamp.from(NOW.minusSeconds(604_800)),
                Timestamp.from(NOW),
                Timestamp.from(NOW),
                Timestamp.from(NOW),
                Timestamp.from(NOW),
                Timestamp.from(NOW.minusSeconds(60)),
                Timestamp.from(NOW)
        );
        return id;
    }

    private PersistedWeeklySnapshot persistSnapshot(
            UUID storeId,
            UUID employeeId,
            UUID syncJobId
    ) {
        Fact storeRevenue = fact(
                "STORE.NET_REVENUE.DELTA", "NET_REVENUE", Unit.MONEY,
                "120000", "100000", "20000", "20"
        );
        Fact storeMargin = fact(
                "STORE.MARGIN_PERCENT.DELTA", "MARGIN_PERCENT", Unit.PERCENT,
                "25", "24", "1", "4.1667"
        );
        Fact storeServiceShare = fact(
                "STORE.GROUP:SERVICE.REVENUE_SHARE_PERCENT.DELTA",
                "REVENUE_SHARE_PERCENT", Unit.PERCENT,
                "12.5", "10", "2.5", "25"
        );
        Fact teamServiceLeader = fact(
                "TEAM.COMPETENCY:SERVICE_SALES.LEADERS",
                "RATING_ELIGIBLE_COUNT", Unit.COUNT, "1"
        );
        Fact employeeWorkloadStatus = fact(
                "EMP:E01.WORKLOAD.STATUS",
                "WORKLOAD_STATUS", Unit.STATUS, "SUFFICIENT"
        );
        Fact employeeWorkloadSufficiency = fact(
                "EMP:E01.WORKLOAD.SUFFICIENCY",
                "WORKLOAD_STATUS", Unit.STATUS, "SUFFICIENT"
        );
        Fact employeeServiceShare = fact(
                "EMP:E01.GROUP:SERVICE.REVENUE_SHARE_PERCENT.CURRENT",
                "REVENUE_SHARE_PERCENT", Unit.PERCENT,
                "18", "14", "4", "28.5714"
        );
        SnapshotEmployeeMembership membership = new SnapshotEmployeeMembership(
                employeeId, "E01", "Анна"
        );
        WeeklySnapshotPayload payload = new WeeklySnapshotPayload(
                1,
                new Manifest(
                        List.of("E01"),
                        List.of(
                                evidence(storeRevenue, Scope.STORE, null),
                                evidence(storeMargin, Scope.STORE, null),
                                evidence(storeServiceShare, Scope.STORE, null),
                                evidence(
                                        teamServiceLeader, Scope.TEAM, null
                                ),
                                evidence(
                                        employeeWorkloadStatus,
                                        Scope.EMPLOYEE, "E01"
                                ),
                                evidence(
                                        employeeWorkloadSufficiency,
                                        Scope.EMPLOYEE, "E01"
                                ),
                                evidence(
                                        employeeServiceShare,
                                        Scope.EMPLOYEE, "E01"
                                )
                        ),
                        List.of(),
                        List.of("SERVICE"),
                        Map.of("SERVICE", "Услуги"),
                        List.of("SERVICE_SALES"),
                        List.of()
                ),
                new Facts(
                        List.of(storeRevenue, storeMargin, storeServiceShare),
                        List.of(teamServiceLeader),
                        List.of(new EmployeeFacts(
                                "E01",
                                Sufficiency.SUFFICIENT,
                                List.of("RESULT", "CATEGORIES"),
                                List.of(
                                        employeeWorkloadStatus,
                                        employeeWorkloadSufficiency,
                                        employeeServiceShare
                                )
                        )),
                        List.of()
                )
        );
        List<SnapshotEmployeeMembership> employees = List.of(membership);
        WeeklySnapshotDraft draft = new WeeklySnapshotDraft(
                storeId,
                new WeeklyAnalyticsFactsQuery(
                        storeId,
                        new StoreKpiPeriod(PERIOD_START, PERIOD_END),
                        new StoreKpiPeriod(
                                PERIOD_START.minusWeeks(1),
                                PERIOD_END.minusWeeks(1)
                        )
                ),
                "Europe/Moscow",
                QualityStatus.READY,
                WeeklySnapshotPolicyV1.VERSIONS,
                employees,
                payload,
                snapshotCodec.hash(payload, employees)
        );
        return snapshotStore.persist(new WeeklySnapshotPersistenceCommand(
                draft,
                syncJobId,
                NOW.minusSeconds(60),
                NOW.minusSeconds(60),
                WeeklySnapshotRevisionReason.AUTO_REVISION,
                null
        )).snapshot();
    }

    private EvidenceIndexEntry evidence(
            Fact fact,
            Scope scope,
            String employeeRef
    ) {
        return new EvidenceIndexEntry(
                fact.evidenceRef(), scope, employeeRef, true
        );
    }

    private Fact fact(
            String evidenceRef,
            String metricCode,
            Unit unit,
            String value
    ) {
        return new Fact(
                evidenceRef,
                metricCode,
                null,
                unit,
                unit == Unit.STATUS ? value : new BigDecimal(value),
                null,
                Sufficiency.SUFFICIENT,
                Materiality.PRIMARY
        );
    }

    private Fact fact(
            String evidenceRef,
            String metricCode,
            Unit unit,
            String value,
            String previous,
            String delta,
            String relativeDelta
    ) {
        return new Fact(
                evidenceRef,
                metricCode,
                null,
                unit,
                new BigDecimal(value),
                new Comparison(
                        new BigDecimal(previous),
                        new BigDecimal(delta),
                        new BigDecimal(relativeDelta)
                ),
                Sufficiency.SUFFICIENT,
                Materiality.PRIMARY
        );
    }

    private PublishedInterpretation insertPublishedInterpretation(
            UUID storeId,
            UUID snapshotId,
            CanonicalLlmJson content
    ) {
        UUID jobId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO llm_analysis_jobs (
                    id, snapshot_id, generation_revision, trigger_type,
                    provider_code, requested_model, provider_config_version,
                    content_schema_version, prompt_version,
                    analysis_policy_version, budget_policy_version,
                    input_hash, status, phase, attempt_count,
                    max_transport_retries, max_validation_retries,
                    next_attempt_at, deadline_at, started_at, finished_at,
                    created_at
                ) VALUES (
                    ?, ?, 1, 'INITIAL', 'TEST', 'test-model', 'test-config',
                    2, 'weekly-interpretation-v4', 'analysis-v1', 'budget-v1',
                    ?, 'SUCCESS', 'PUBLISH', 1, 1, 1, ?, ?, ?, ?, ?
                )
                """,
                jobId,
                snapshotId,
                "1".repeat(64),
                Timestamp.from(NOW),
                Timestamp.from(NOW.plusSeconds(3600)),
                Timestamp.from(NOW.minusSeconds(30)),
                Timestamp.from(NOW),
                Timestamp.from(NOW.minusSeconds(60))
        );
        UUID attemptId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO llm_analysis_attempts (
                    id, job_id, attempt_number, attempt_type, status,
                    provider_code, requested_model, request_hash,
                    response_hash, response_body,
                    validated_response_hash, validated_response_body,
                    started_at, response_received_at, finished_at
                ) VALUES (
                    ?, ?, 1, 'INITIAL', 'SUCCEEDED', 'TEST', 'test-model',
                    ?, ?, ?, ?, ?, ?, ?, ?
                )
                """,
                attemptId,
                jobId,
                "2".repeat(64),
                content.contentHash(),
                content.canonicalJson(),
                content.contentHash(),
                content.canonicalJson(),
                Timestamp.from(NOW.minusSeconds(20)),
                Timestamp.from(NOW.minusSeconds(10)),
                Timestamp.from(NOW)
        );
        UUID interpretationId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO llm_interpretations (
                    id, store_id, snapshot_id, analysis_job_id,
                    successful_attempt_id, period_start, period_end, revision,
                    publication_reason_code, content_payload, content_hash,
                    validated_at, published_at
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, 1, 'INITIAL', CAST(? AS jsonb), ?, ?, ?
                )
                """,
                interpretationId,
                storeId,
                snapshotId,
                jobId,
                attemptId,
                PERIOD_START,
                PERIOD_END,
                content.canonicalJson(),
                content.contentHash(),
                Timestamp.from(NOW),
                Timestamp.from(NOW)
        );
        return new PublishedInterpretation(interpretationId);
    }

    private UUID insertEvent(
            UUID storeId,
            UUID snapshotId,
            UUID interpretationId
    ) {
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO notification_events (
                    id, store_id, event_type, audience, interpretation_id,
                    snapshot_id, deduplication_key, notification_policy_version,
                    priority, event_payload, payload_hash, not_before, expires_at
                ) VALUES (
                    ?, ?, 'WEEKLY_REPORT_READY', 'MANAGER', ?, ?, ?,
                    'weekly-notification-v1', 'NORMAL', '{}'::jsonb, ?, ?, ?
                )
                """,
                eventId,
                storeId,
                interpretationId,
                snapshotId,
                "v2-acceptance:" + eventId,
                "3".repeat(64),
                Timestamp.from(NOW.minusSeconds(1)),
                Timestamp.from(NOW.plusSeconds(86_400))
        );
        return eventId;
    }

    private void insertRecipient(UUID storeId) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO app_users (id, email, password_hash, display_name, role) "
                        + "VALUES (?, ?, '{noop}test', 'Manager', 'MANAGER')",
                userId,
                "v2-manager-" + userId + "@example.test"
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
                Timestamp.from(NOW)
        );
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock acceptanceClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    private record PublishedInterpretation(UUID interpretationId) {
    }

    private record Fixture(
            UUID storeId,
            UUID employeeId,
            UUID eventId,
            UUID interpretationId,
            String contentHash
    ) {
    }
}
