package com.storeanalytics.interpretation.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeanalytics.interpretation.config.LlmAnalysisWorkerProperties;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class LlmProviderCallPipelineIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-03T05:00:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private LlmAnalysisJobStore jobStore;

    @Autowired
    private LlmProviderCallClaimStore claimStore;

    @Autowired
    private LlmAnalysisAttemptStore attemptStore;

    @Autowired
    private LlmAnalysisPhaseTransitionStore transitionStore;

    @Autowired
    private LlmProviderFailureTransitionStore failureTransitionStore;

    @Autowired
    private LlmPreflightFailureTransitionStore preflightFailureStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void callsProviderOncePersistsResponseAndHandsOffToValidationPhase() {
        UUID snapshotId = createSnapshot();
        LlmAnalysisJob queued = jobStore.enqueue(request(snapshotId), NOW).job();
        LlmAnalysisJob claimed = claimStore.claimNext(
                "provider-worker", Duration.ofMinutes(2), NOW
        ).orElseThrow();
        LlmAnalysisWorkerProperties properties = properties();
        LlmProviderRequest providerRequest = new LlmProviderRequest(
                claimed.id(),
                "TEST",
                "test-model",
                "system prompt",
                "{}",
                "{}",
                new BigDecimal("0.2"),
                1_000,
                NOW.plusSeconds(91)
        );
        PreparedLlmProviderRequest prepared = new PreparedLlmProviderRequest(
                providerRequest,
                "c".repeat(64)
        );
        LlmProviderRequestFactory requestFactory = mock(LlmProviderRequestFactory.class);
        when(requestFactory.prepare(
                claimed,
                NOW.plusSeconds(1),
                properties.providerCallTimeout()
        )).thenReturn(prepared);
        TestProvider provider = new TestProvider();
        LlmProviderCallExecutionService execution =
                new LlmProviderCallExecutionService(
                        new LlmProviderRegistry(List.of(provider)),
                        requestFactory,
                        new LlmProviderBudgetGuard(properties),
                        new LlmProviderCallPersistence(
                                attemptStore,
                                transitionStore,
                                failureTransitionStore,
                                preflightFailureStore
                        ),
                        properties,
                        mock(LlmProviderOrchestrationMetrics.class),
                        Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC)
                );

        LlmAnalysisJob handedOff = execution.execute(claimed, "provider-worker");

        assertThat(handedOff.id()).isEqualTo(queued.id());
        assertThat(handedOff.status()).isEqualTo(LlmAnalysisJobStatus.WAITING_RETRY);
        assertThat(handedOff.phase()).isEqualTo(LlmAnalysisPhase.VALIDATE_RESPONSE);
        assertThat(handedOff.leaseOwner()).isNull();
        assertThat(provider.calls()).isOne();
        LlmAnalysisAttempt received = attemptStore.findOpenByJobId(queued.id())
                .orElseThrow();
        assertThat(received.status())
                .isEqualTo(LlmAnalysisAttemptStatus.RESPONSE_RECEIVED);
        assertThat(received.providerRequestId()).isEqualTo("test-request-1");
        assertThat(received.totalTokens()).isEqualTo(140);
        assertThat(claimStore.claimNext(
                "another-provider-worker", Duration.ofMinutes(2), NOW.plusSeconds(2)
        )).isEmpty();
    }

    private LlmAnalysisWorkerProperties properties() {
        return new LlmAnalysisWorkerProperties(
                false,
                Duration.ofSeconds(5),
                Duration.ofMinutes(2),
                Duration.ofSeconds(15),
                Duration.ofSeconds(30),
                Duration.ofSeconds(90),
                524_288,
                new BigDecimal("50.00")
        );
    }

    private LlmAnalysisJobRequest request(UUID snapshotId) {
        return new LlmAnalysisJobRequest(
                snapshotId,
                1,
                LlmAnalysisTriggerType.INITIAL,
                null,
                "TEST",
                "test-model",
                "test-provider-v1",
                1,
                "weekly-interpretation-v1",
                "weekly-analysis-v1",
                "weekly-budget-v1",
                "{\"maxOutputTokens\":1000,\"maxProviderCalls\":2,\"temperature\":0.2}",
                "a".repeat(64),
                1,
                1,
                NOW.plus(Duration.ofMinutes(5))
        );
    }

    private UUID createSnapshot() {
        UUID connectionId = jdbcTemplate.queryForObject(
                "SELECT id FROM integration_connections WHERE connection_key = ?",
                UUID.class,
                "livesklad-default"
        );
        UUID storeId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO stores (
                    id, connection_id, source_system, external_id, name, timezone
                ) VALUES (?, ?, 'LIVESKLAD', ?, 'Provider pipeline', 'Europe/Kaliningrad')
                """,
                storeId,
                connectionId,
                "provider-pipeline-" + storeId
        );
        UUID syncJobId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO sync_jobs (
                    id, connection_id, job_type, status, phase, period_start, period_end,
                    cursor_start, current_window_end, window_size_minutes, max_attempts,
                    next_attempt_at, started_at, finished_at
                ) VALUES (
                    ?, ?, 'INCREMENTAL', 'SUCCESS', 'RETURNS', ?, ?, ?, ?, 1440, 5,
                    ?, ?, ?
                )
                """,
                syncJobId,
                connectionId,
                Timestamp.from(NOW.minus(Duration.ofDays(7))),
                Timestamp.from(NOW),
                Timestamp.from(NOW),
                Timestamp.from(NOW),
                Timestamp.from(NOW),
                Timestamp.from(NOW.minusSeconds(60)),
                Timestamp.from(NOW)
        );
        UUID snapshotId = UUID.randomUUID();
        LocalDate start = LocalDate.of(2026, 7, 27);
        jdbcTemplate.update(
                """
                INSERT INTO analytics_snapshots (
                    id, store_id, period_start, period_end, timezone, revision,
                    revision_reason_code, source_sync_job_id, source_sync_completed_at,
                    source_data_cutoff, facts_schema_version, metrics_contract_version,
                    calculation_version, quality_policy_version, quality_status,
                    facts_payload, facts_hash, created_at
                ) VALUES (
                    ?, ?, ?, ?, 'Europe/Kaliningrad', 1, 'INITIAL', ?, ?, ?, 1,
                    'weekly-metrics-v1', 'weekly-snapshot-v1', 'weekly-quality-v1',
                    'READY', '{}'::jsonb, ?, ?
                )
                """,
                snapshotId,
                storeId,
                start,
                start.plusDays(6),
                syncJobId,
                Timestamp.from(NOW),
                Timestamp.from(NOW),
                "b".repeat(64),
                Timestamp.from(NOW)
        );
        return snapshotId;
    }

    private static final class TestProvider implements LlmProviderClient {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String providerCode() {
            return "TEST";
        }

        @Override
        public LlmProviderPreflight preflight(LlmProviderRequest request) {
            return new LlmProviderPreflight(
                    100,
                    8_000,
                    new BigDecimal("1.00"),
                    "RUB"
            );
        }

        @Override
        public LlmProviderResponseReceipt generate(LlmProviderRequest request) {
            calls.incrementAndGet();
            return new LlmProviderResponseReceipt(
                    "{\"status\":\"ok\"}",
                    "test-model-v1",
                    "test-request-1",
                    100,
                    40,
                    0,
                    null,
                    140,
                    new BigDecimal("0.50"),
                    "RUB",
                    250L,
                    200
            );
        }

        int calls() {
            return calls.get();
        }
    }
}
