package com.storeanalytics.interpretation.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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
class LlmProviderFailureTransitionStoreIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-03T06:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final Duration FALLBACK_RETRY = Duration.ofSeconds(30);

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
    private LlmProviderFailureTransitionStore failureStore;

    @Autowired
    private LlmAnalysisJobLifecycleStore lifecycleStore;

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
    void persistsKnownTransientFailureAndHonorsLongerRetryAfter() {
        ClaimedAttempt claimed = startInitialAttempt("rate-limit-worker");
        TestProviderException failure = new TestProviderException(
                "RATE_LIMITED",
                LlmProviderOutcome.RESPONSE_RECEIVED,
                true,
                429,
                Duration.ofSeconds(60),
                "Provider rate limit was reached"
        );

        LlmAnalysisJob result = failureStore.recordFailure(
                claimed.job().id(),
                claimed.attempt().id(),
                "rate-limit-worker",
                failure,
                FALLBACK_RETRY,
                NOW
        );

        assertThat(result.status()).isEqualTo(LlmAnalysisJobStatus.WAITING_RETRY);
        assertThat(result.phase()).isEqualTo(LlmAnalysisPhase.CALL_PROVIDER);
        assertThat(result.transportRetryCount()).isOne();
        assertThat(result.nextAttemptAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(result.leaseOwner()).isNull();
        LlmAnalysisAttempt attempt = attemptStore.findById(claimed.attempt().id())
                .orElseThrow();
        assertThat(attempt.status())
                .isEqualTo(LlmAnalysisAttemptStatus.TRANSIENT_FAILED);
        assertThat(attempt.httpStatus()).isEqualTo(429);
        assertThat(attempt.errorCode()).isEqualTo("LLM_PROVIDER_RATE_LIMITED");
        assertThat(attempt.errorSummary())
                .isEqualTo("Provider rate limit was reached");

        lifecycleStore.requestCancellation(result.id(), NOW.plusSeconds(1));
    }

    @Test
    void marksAmbiguousTransportFailureAsUnknownOutcome() {
        ClaimedAttempt claimed = startInitialAttempt("timeout-worker");
        TestProviderException failure = new TestProviderException(
                "DEADLINE_EXCEEDED",
                LlmProviderOutcome.UNKNOWN,
                true,
                null,
                null,
                "Provider request timed out"
        );

        LlmAnalysisJob result = failureStore.recordFailure(
                claimed.job().id(),
                claimed.attempt().id(),
                "timeout-worker",
                failure,
                FALLBACK_RETRY,
                NOW
        );

        assertThat(result.status()).isEqualTo(LlmAnalysisJobStatus.WAITING_RETRY);
        assertThat(result.nextAttemptAt()).isEqualTo(NOW.plus(FALLBACK_RETRY));
        LlmAnalysisAttempt attempt = attemptStore.findById(claimed.attempt().id())
                .orElseThrow();
        assertThat(attempt.status())
                .isEqualTo(LlmAnalysisAttemptStatus.UNKNOWN_OUTCOME);
        assertThat(attempt.errorCode())
                .isEqualTo("LLM_PROVIDER_DEADLINE_EXCEEDED");

        lifecycleStore.requestCancellation(result.id(), NOW.plusSeconds(1));
    }

    @Test
    void terminatesKnownPermanentProviderFailureWithoutRetry() {
        ClaimedAttempt claimed = startInitialAttempt("auth-worker");
        TestProviderException failure = new TestProviderException(
                "AUTHENTICATION",
                LlmProviderOutcome.NOT_SENT,
                false,
                401,
                null,
                "Provider credentials were rejected"
        );

        LlmAnalysisJob result = failureStore.recordFailure(
                claimed.job().id(),
                claimed.attempt().id(),
                "auth-worker",
                failure,
                FALLBACK_RETRY,
                NOW
        );

        assertThat(result.status()).isEqualTo(LlmAnalysisJobStatus.FAILED);
        assertThat(result.terminalReasonCode())
                .isEqualTo("LLM_PROVIDER_AUTHENTICATION");
        assertThat(result.transportRetryCount()).isZero();
        assertThat(result.finishedAt()).isEqualTo(NOW);
        LlmAnalysisAttempt attempt = attemptStore.findById(claimed.attempt().id())
                .orElseThrow();
        assertThat(attempt.status())
                .isEqualTo(LlmAnalysisAttemptStatus.PERMANENT_FAILED);
        assertThat(attempt.httpStatus()).isEqualTo(401);
    }

    @Test
    void stopsAfterTheReservedTransportRetryIsExhausted() {
        ClaimedAttempt first = startInitialAttempt("retry-worker-1");
        TestProviderException failure = new TestProviderException(
                "TRANSIENT_PROVIDER",
                LlmProviderOutcome.RESPONSE_RECEIVED,
                true,
                503,
                null,
                "Provider is temporarily unavailable"
        );
        LlmAnalysisJob waiting = failureStore.recordFailure(
                first.job().id(),
                first.attempt().id(),
                "retry-worker-1",
                failure,
                FALLBACK_RETRY,
                NOW
        );
        LlmAnalysisJob retryClaim = claimStore.claimNext(
                "retry-worker-2", LEASE, NOW.plusSeconds(31)
        ).orElseThrow();
        assertThat(retryClaim.id()).isEqualTo(waiting.id());
        LlmAnalysisAttempt retryAttempt = attemptStore.startProviderCall(
                retryClaim.id(),
                "retry-worker-2",
                LlmAnalysisAttemptType.TRANSPORT_RETRY,
                "d".repeat(64),
                NOW.plusSeconds(31)
        );

        LlmAnalysisJob result = failureStore.recordFailure(
                retryClaim.id(),
                retryAttempt.id(),
                "retry-worker-2",
                failure,
                FALLBACK_RETRY,
                NOW.plusSeconds(31)
        );

        assertThat(result.status()).isEqualTo(LlmAnalysisJobStatus.FAILED);
        assertThat(result.terminalReasonCode()).isEqualTo(
                LlmAnalysisJobLifecycleStore.TRANSPORT_RETRIES_EXHAUSTED
        );
        assertThat(result.transportRetryCount()).isOne();
        LlmAnalysisAttempt persistedRetry = attemptStore.findById(retryAttempt.id())
                .orElseThrow();
        assertThat(persistedRetry.status())
                .isEqualTo(LlmAnalysisAttemptStatus.TRANSIENT_FAILED);
    }

    @Test
    void terminatesBudgetRejectionWithoutCreatingProviderAttempt() {
        UUID snapshotId = createSnapshot();
        LlmAnalysisJob queued = jobStore.enqueue(request(snapshotId), NOW).job();
        LlmAnalysisJob claimed = claimStore.claimNext(
                "budget-worker", LEASE, NOW
        ).orElseThrow();
        assertThat(claimed.id()).isEqualTo(queued.id());

        LlmAnalysisJob result = preflightFailureStore.recordRejection(
                claimed.id(),
                "budget-worker",
                "LLM_PREFLIGHT_COST_BUDGET_EXCEEDED",
                "LLM request exceeds configured cost budget",
                NOW
        );

        assertThat(result.status()).isEqualTo(LlmAnalysisJobStatus.FAILED);
        assertThat(result.terminalReasonCode())
                .isEqualTo("LLM_PREFLIGHT_COST_BUDGET_EXCEEDED");
        assertThat(result.errorSummary())
                .isEqualTo("LLM request exceeds configured cost budget");
        assertThat(result.transportRetryCount()).isZero();
        assertThat(attemptStore.findOpenByJobId(result.id())).isEmpty();
        Integer attempts = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM llm_analysis_attempts WHERE job_id = ?",
                Integer.class,
                result.id()
        );
        assertThat(attempts).isZero();
    }

    private ClaimedAttempt startInitialAttempt(String owner) {
        UUID snapshotId = createSnapshot();
        LlmAnalysisJob queued = jobStore.enqueue(request(snapshotId), NOW).job();
        LlmAnalysisJob claimed = claimStore.claimNext(owner, LEASE, NOW)
                .orElseThrow();
        assertThat(claimed.id()).isEqualTo(queued.id());
        LlmAnalysisAttempt attempt = attemptStore.startProviderCall(
                claimed.id(),
                owner,
                LlmAnalysisAttemptType.INITIAL,
                "c".repeat(64),
                NOW
        );
        return new ClaimedAttempt(claimed, attempt);
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
                ) VALUES (?, ?, 'LIVESKLAD', ?, 'Provider failure', 'Europe/Moscow')
                """,
                storeId,
                connectionId,
                "provider-failure-" + storeId
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
                    ?, ?, ?, ?, 'Europe/Moscow', 1, 'INITIAL', ?, ?, ?, 1,
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

    private record ClaimedAttempt(
            LlmAnalysisJob job,
            LlmAnalysisAttempt attempt
    ) {
    }

    private static final class TestProviderException extends LlmProviderException {

        private final String failureCode;
        private final LlmProviderOutcome outcome;
        private final boolean retryable;
        private final Integer httpStatus;
        private final Duration retryAfter;

        private TestProviderException(
                String failureCode,
                LlmProviderOutcome outcome,
                boolean retryable,
                Integer httpStatus,
                Duration retryAfter,
                String safeMessage
        ) {
            super(safeMessage, null);
            this.failureCode = failureCode;
            this.outcome = outcome;
            this.retryable = retryable;
            this.httpStatus = httpStatus;
            this.retryAfter = retryAfter;
        }

        @Override
        public String failureCode() {
            return failureCode;
        }

        @Override
        public LlmProviderOutcome outcome() {
            return outcome;
        }

        @Override
        public Integer httpStatus() {
            return httpStatus;
        }

        @Override
        public Duration retryAfter() {
            return retryAfter;
        }

        @Override
        public boolean isRetryable() {
            return retryable;
        }
    }
}
