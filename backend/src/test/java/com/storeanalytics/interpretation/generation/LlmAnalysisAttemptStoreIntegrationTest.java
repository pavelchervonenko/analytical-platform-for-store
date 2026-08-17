package com.storeanalytics.interpretation.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
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

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class LlmAnalysisAttemptStoreIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-03T05:00:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private LlmAnalysisJobStore jobStore;

    @Autowired
    private LlmAnalysisJobClaimStore claimStore;

    @Autowired
    private LlmAnalysisJobLifecycleStore lifecycleStore;

    @Autowired
    private LlmAnalysisAttemptStore attemptStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void persistsResponseBeforeValidationAndRecoversWithoutAnotherProviderCall() {
        UUID snapshotId = createSnapshot(NOW, "response-recovery");
        LlmAnalysisJob queued = jobStore.enqueue(request(snapshotId, NOW), NOW).job();
        LlmAnalysisJob claimed = claimStore.claimNext(
                "worker-response", Duration.ofSeconds(10), NOW
        ).orElseThrow();
        String providerInput = "{\"contractVersion\":1}";

        LlmAnalysisAttempt started = attemptStore.startProviderCall(
                claimed.id(), "worker-response", LlmAnalysisAttemptType.INITIAL,
                "c".repeat(64), providerInput, NOW.plusSeconds(1)
        );
        assertThat(started.status()).isEqualTo(LlmAnalysisAttemptStatus.STARTED);
        assertThat(started.attemptNumber()).isOne();
        assertThat(started.providerInputBody()).isEqualTo(providerInput);
        assertThat(started.providerInputHash()).isEqualTo(sha256(providerInput));
        assertThat(jobStore.findById(queued.id()).orElseThrow().phase())
                .isEqualTo(LlmAnalysisPhase.CALL_PROVIDER);
        assertThatThrownBy(() -> attemptStore.startProviderCall(
                claimed.id(), "worker-response", LlmAnalysisAttemptType.INITIAL,
                "c".repeat(64), NOW.plusSeconds(2)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unfinished provider attempt");

        String body = "{\"summary\":\"response is durable\"}";
        LlmProviderResponseReceipt receipt = new LlmProviderResponseReceipt(
                body,
                "yandexgpt/latest",
                "request-123",
                120,
                40,
                0,
                null,
                160,
                new BigDecimal("0.012300"),
                "RUB",
                450L,
                200
        );
        LlmAnalysisAttempt received = attemptStore.recordProviderResponse(
                started.id(), "worker-response", receipt, NOW.plusSeconds(3)
        );
        assertThat(received.status())
                .isEqualTo(LlmAnalysisAttemptStatus.RESPONSE_RECEIVED);
        assertThat(received.responseHash()).isEqualTo(sha256(body));
        assertThat(attemptStore.recordProviderResponse(
                started.id(), "worker-response", receipt, NOW.plusSeconds(4)
        ).id()).isEqualTo(started.id());

        LlmAnalysisJob recovered = lifecycleStore.recoverOneExpiredLease(
                NOW.plusSeconds(20), NOW.plusSeconds(11)
        ).orElseThrow();
        assertThat(recovered.status()).isEqualTo(LlmAnalysisJobStatus.WAITING_RETRY);
        assertThat(recovered.phase()).isEqualTo(LlmAnalysisPhase.VALIDATE_RESPONSE);
        assertThat(recovered.transportRetryCount()).isZero();
        assertThat(attemptStore.findOpenByJobId(recovered.id()).orElseThrow().id())
                .isEqualTo(started.id());

        LlmAnalysisJob cancelled = lifecycleStore.requestCancellation(
                recovered.id(), NOW.plusSeconds(12)
        );
        assertThat(cancelled.status()).isEqualTo(LlmAnalysisJobStatus.CANCELLED);
        assertThat(attemptStore.findById(started.id()).orElseThrow().status())
                .isEqualTo(LlmAnalysisAttemptStatus.CANCELLED);
    }

    @Test
    void unknownOutcomeConsumesTransportBudgetAndStopsAfterLastRetry() {
        Instant start = NOW.plus(Duration.ofMinutes(10));
        UUID snapshotId = createSnapshot(start, "unknown-outcome");
        LlmAnalysisJob queued = jobStore.enqueue(request(snapshotId, start), start).job();
        LlmAnalysisJob claimed = claimStore.claimNext(
                "worker-initial", Duration.ofSeconds(10), start
        ).orElseThrow();
        LlmAnalysisAttempt initial = attemptStore.startProviderCall(
                claimed.id(), "worker-initial", LlmAnalysisAttemptType.INITIAL,
                "d".repeat(64), start.plusSeconds(1)
        );

        LlmAnalysisJob waiting = lifecycleStore.recoverOneExpiredLease(
                start.plusSeconds(20), start.plusSeconds(11)
        ).orElseThrow();
        assertThat(waiting.id()).isEqualTo(queued.id());
        assertThat(waiting.status()).isEqualTo(LlmAnalysisJobStatus.WAITING_RETRY);
        assertThat(waiting.phase()).isEqualTo(LlmAnalysisPhase.CALL_PROVIDER);
        assertThat(waiting.transportRetryCount()).isOne();
        LlmAnalysisAttempt unknown = attemptStore.findById(initial.id()).orElseThrow();
        assertThat(unknown.status()).isEqualTo(LlmAnalysisAttemptStatus.UNKNOWN_OUTCOME);
        assertThat(unknown.errorCode())
                .isEqualTo(LlmAnalysisJobLifecycleStore.PROVIDER_OUTCOME_UNKNOWN);

        LlmAnalysisJob reclaimed = claimStore.claimNext(
                "worker-retry", Duration.ofSeconds(10), start.plusSeconds(20)
        ).orElseThrow();
        LlmAnalysisAttempt retry = attemptStore.startProviderCall(
                reclaimed.id(), "worker-retry", LlmAnalysisAttemptType.TRANSPORT_RETRY,
                "e".repeat(64), start.plusSeconds(21)
        );
        assertThat(retry.attemptNumber()).isEqualTo(2);
        assertThat(retry.attemptType())
                .isEqualTo(LlmAnalysisAttemptType.TRANSPORT_RETRY);

        LlmAnalysisJob failed = lifecycleStore.recoverOneExpiredLease(
                start.plusSeconds(40), start.plusSeconds(31)
        ).orElseThrow();
        assertThat(failed.status()).isEqualTo(LlmAnalysisJobStatus.FAILED);
        assertThat(failed.terminalReasonCode())
                .isEqualTo(LlmAnalysisJobLifecycleStore.TRANSPORT_RETRIES_EXHAUSTED);
        assertThat(failed.transportRetryCount()).isOne();
        assertThat(attemptStore.findById(retry.id()).orElseThrow().status())
                .isEqualTo(LlmAnalysisAttemptStatus.UNKNOWN_OUTCOME);
    }

    private LlmAnalysisJobRequest request(UUID snapshotId, Instant start) {
        return new LlmAnalysisJobRequest(
                snapshotId,
                1,
                LlmAnalysisTriggerType.INITIAL,
                null,
                "YANDEX",
                "gpt://folder/yandexgpt/latest",
                "yandex-foundation-models-v1",
                1,
                "weekly-interpretation-v1",
                "weekly-analysis-v1",
                "weekly-budget-v1",
                "{\"maxOutputTokens\":4000,\"maxProviderCalls\":2,\"temperature\":0.2}",
                "a".repeat(64),
                1,
                1,
                start.plus(Duration.ofMinutes(5))
        );
    }

    private UUID createSnapshot(Instant timestamp, String suffix) {
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
                ) VALUES (?, ?, 'LIVESKLAD', ?, 'LLM attempt store', 'Europe/Kaliningrad')
                """,
                storeId,
                connectionId,
                "llm-attempt-" + suffix + "-" + storeId
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
                Timestamp.from(timestamp.minus(Duration.ofDays(7))),
                Timestamp.from(timestamp),
                Timestamp.from(timestamp),
                Timestamp.from(timestamp),
                Timestamp.from(timestamp),
                Timestamp.from(timestamp.minusSeconds(60)),
                Timestamp.from(timestamp)
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
                Timestamp.from(timestamp),
                Timestamp.from(timestamp),
                "b".repeat(64),
                Timestamp.from(timestamp)
        );
        return snapshotId;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8)
            );
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
