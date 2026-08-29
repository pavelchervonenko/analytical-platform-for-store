package com.storeanalytics.interpretation.review.ai;

import static com.storeanalytics.interpretation.review.WeeklyReviewTestPayload.snapshotPayload;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.interpretation.generation.LlmProviderPreflight;
import com.storeanalytics.interpretation.generation.LlmProviderRequest;
import com.storeanalytics.interpretation.generation.LlmProviderResponseReceipt;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class WeeklyReviewAiJobStoreIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private WeeklyReviewAiJobStore store;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void plansOnlyLatestEligibleRevisionPerStoreIdempotently() {
        UUID firstStore = addStore("AI planner first");
        UUID oldSnapshot = addSnapshot(
                firstStore, LocalDate.of(2026, 8, 10), 1
        );
        UUID latestSnapshot = addSnapshot(
                firstStore, LocalDate.of(2026, 8, 17), 1
        );
        UUID secondStore = addStore("AI planner second");
        UUID secondSnapshot = addSnapshot(
                secondStore, LocalDate.of(2026, 8, 17), 1
        );

        assertThat(store.enqueueLatest(
                "YANDEX", "gpt://folder/yandexgpt-5.1", 2, 10,
                NOW, Duration.ofHours(2)
        )).isEqualTo(2);
        assertThat(store.enqueueLatest(
                "YANDEX", "gpt://folder/yandexgpt-5.1", 2, 10,
                NOW, Duration.ofHours(2)
        )).isZero();
        assertThat(store.findBySnapshot(oldSnapshot)).isEmpty();
        assertThat(store.findBySnapshot(latestSnapshot)).isPresent();
        assertThat(store.findBySnapshot(secondSnapshot)).isPresent();
    }

    @Test
    void doesNotFallBackToOlderReadySnapshotWhenLatestIsBlocked() {
        UUID storeId = addStore("AI planner blocked latest");
        UUID oldReady = addSnapshot(
                storeId, LocalDate.of(2026, 8, 10), 1
        );
        UUID latestBlocked = addSnapshot(
                storeId, LocalDate.of(2026, 8, 17), 1, "BLOCKED"
        );

        assertThat(store.enqueueLatest(
                "YANDEX", "gpt://folder/yandexgpt-5.1", 2, 10,
                NOW, Duration.ofHours(2)
        )).isZero();
        assertThat(store.findBySnapshot(oldReady)).isEmpty();
        assertThat(store.findBySnapshot(latestBlocked)).isEmpty();
    }

    @Test
    void activeWorkerLeavesPendingLegacyJobUntouched() {
        Instant legacyNow = NOW.minus(Duration.ofDays(365));
        UUID snapshotId = addSnapshot(
                addStore("AI legacy pending"), LocalDate.of(2025, 8, 17), 1,
                "BLOCKED"
        );
        long activePendingBefore = store.countByStatus(
                WeeklyReviewAiJobStatus.PENDING
        );
        UUID legacyJobId = addLegacyJob(snapshotId, legacyNow);

        assertThat(store.countByStatus(WeeklyReviewAiJobStatus.PENDING))
                .isEqualTo(activePendingBefore);

        assertThat(store.claimNext(
                "v23-worker", Duration.ofMinutes(4), legacyNow
        )).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM weekly_review_ai_jobs WHERE id = ?",
                String.class,
                legacyJobId
        )).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM weekly_review_ai_jobs WHERE id = ?",
                Integer.class,
                legacyJobId
        )).isZero();
    }

    @Test
    void retriesSemanticFailureThenFinalizesImmutableSuccess() {
        UUID snapshotId = addSnapshot(
                addStore("AI retry"), LocalDate.of(2026, 8, 17), 1
        );
        WeeklyReviewAiJob pending = store.enqueue(
                snapshotId, "YANDEX", "gpt://folder/yandexgpt-5.1", 2,
                NOW, Duration.ofHours(2)
        );
        WeeklyReviewAiJob firstClaim = store.claimNext(
                "worker-1", Duration.ofMinutes(4), NOW
        ).orElseThrow();
        assertThat(firstClaim.id()).isEqualTo(pending.id());
        WeeklyReviewAiInput input = input();
        WeeklyReviewAiAttempt firstAttempt = store.startAttempt(
                firstClaim,
                "worker-1",
                prepared(firstClaim, input),
                preflight(),
                NOW
        );
        WeeklyReviewAiValidationResult invalid = validator().validate(
                input,
                response("Чистая выручка выросла на 12%.")
        );
        assertThat(invalid.semanticValidated()).isFalse();
        store.recordValidationFailure(
                firstClaim,
                firstAttempt,
                "worker-1",
                receipt(response("Чистая выручка выросла на 12%.")),
                invalid,
                Duration.ofSeconds(30),
                NOW.plusSeconds(1)
        );

        WeeklyReviewAiJob retry = store.findById(pending.id()).orElseThrow();
        assertThat(retry.status()).isEqualTo(WeeklyReviewAiJobStatus.RETRY_WAIT);
        assertThat(retry.attemptCount()).isOne();
        assertThat(retry.lastValidationCodes()).contains("UNAPPROVED_NUMBER");
        WeeklyReviewAiJob secondClaim = store.claimNext(
                "worker-2", Duration.ofMinutes(4), NOW.plusSeconds(31)
        ).orElseThrow();
        WeeklyReviewAiAttempt secondAttempt = store.startAttempt(
                secondClaim,
                "worker-2",
                prepared(secondClaim, input),
                preflight(),
                NOW.plusSeconds(31)
        );
        WeeklyReviewAiValidationResult valid = validator().validate(
                input,
                response("Чистая выручка выросла.")
        );
        store.recordSuccessfulAttempt(
                secondClaim,
                secondAttempt,
                "worker-2",
                receipt(response("Чистая выручка выросла.")),
                valid,
                NOW.plusSeconds(32)
        );

        WeeklyReviewAiJob succeeded = store.findById(pending.id()).orElseThrow();
        assertThat(succeeded.status()).isEqualTo(WeeklyReviewAiJobStatus.SUCCEEDED);
        assertThat(succeeded.attemptCount()).isEqualTo(2);
        assertThat(store.actualCostSince(NOW.minusSeconds(1)))
                .isEqualByComparingTo("4.00");
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                UPDATE weekly_review_ai_attempts
                SET error_code = 'MUTATED'
                WHERE id = ?
                """,
                firstAttempt.id()
        )).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("Final weekly review AI attempts are immutable");
    }

    @Test
    void expiredLeaseClosesStartedAttemptBeforeSafeRetry() {
        UUID snapshotId = addSnapshot(
                addStore("AI lease"), LocalDate.of(2026, 8, 17), 1
        );
        WeeklyReviewAiJob pending = store.enqueue(
                snapshotId, "YANDEX", "gpt://folder/yandexgpt-5.1", 2,
                NOW, Duration.ofHours(2)
        );
        WeeklyReviewAiJob claim = store.claimNext(
                "worker-old", Duration.ofMinutes(4), NOW
        ).orElseThrow();
        WeeklyReviewAiAttempt attempt = store.startAttempt(
                claim, "worker-old", prepared(claim, input()), preflight(), NOW
        );
        jdbcTemplate.update(
                "UPDATE weekly_review_ai_jobs SET lease_until = ? WHERE id = ?",
                java.sql.Timestamp.from(NOW.minusSeconds(1)),
                pending.id()
        );

        WeeklyReviewAiJob reclaimed = store.claimNext(
                "worker-new", Duration.ofMinutes(4), NOW.plusSeconds(1)
        ).orElseThrow();

        assertThat(reclaimed.id()).isEqualTo(pending.id());
        assertThat(reclaimed.attemptCount()).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM weekly_review_ai_attempts WHERE id = ?",
                String.class,
                attempt.id()
        )).isEqualTo("FAILED");
    }

    private UUID addLegacyJob(UUID snapshotId, Instant createdAt) {
        UUID jobId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO weekly_review_ai_jobs (
                    id, snapshot_id, prompt_version, content_schema_version,
                    provider_code, requested_model, status, attempt_count,
                    max_attempts, next_attempt_at, deadline_at,
                    created_at, updated_at
                ) VALUES (?, ?, ?, 4, ?, ?, ?, 0, 2, ?, ?, ?, ?)
                """,
                jobId,
                snapshotId,
                WeeklyReviewAiContract.LEGACY_PROMPT_VERSION,
                "YANDEX",
                "gpt://folder/yandexgpt-5.1",
                "PENDING",
                java.sql.Timestamp.from(createdAt),
                java.sql.Timestamp.from(createdAt.plus(Duration.ofHours(2))),
                java.sql.Timestamp.from(createdAt),
                java.sql.Timestamp.from(createdAt)
        );
        return jobId;
    }

    private PreparedWeeklyReviewAiRequest prepared(
            WeeklyReviewAiJob job,
            WeeklyReviewAiInput input
    ) {
        String inputJson = "{\"contractVersion\":2}";
        LlmProviderRequest request = new LlmProviderRequest(
                job.id(), job.providerCode(), job.requestedModel(), "system",
                inputJson, "{}", new BigDecimal("0.1"), 1400,
                NOW.plus(Duration.ofMinutes(3))
        );
        return new PreparedWeeklyReviewAiRequest(
                request, "b".repeat(64), input, "c".repeat(64)
        );
    }

    private LlmProviderPreflight preflight() {
        return new LlmProviderPreflight(
                1000, 8000, new BigDecimal("3.00"), "RUB"
        );
    }

    private LlmProviderResponseReceipt receipt(String body) {
        return new LlmProviderResponseReceipt(
                body,
                "gpt://folder/yandexgpt-5.1",
                UUID.randomUUID().toString(),
                1000, 100, 0, 0, 1100,
                new BigDecimal("2.00"), "RUB", 500L, 200
        );
    }

    private WeeklyReviewAiSemanticValidator validator() {
        return new WeeklyReviewAiSemanticValidator(
                new WeeklyReviewAiStructuralValidator()
        );
    }

    private WeeklyReviewAiInput input() {
        return new WeeklyReviewAiInput(
                WeeklyReviewAiContract.INPUT_SCHEMA_VERSION,
                WeeklyReviewAiContract.PROMPT_VERSION,
                4,
                new WeeklyReviewAiInput.SummarySource(
                        "Чистая выручка выросла.",
                        "POSITIVE",
                        List.of("Чистая выручка выросла."),
                        List.of("STORE.NET_REVENUE"),
                        List.of()
                ),
                List.of(),
                List.of(),
                List.of(new WeeklyReviewAiInput.EvidenceSource(
                        "STORE.NET_REVENUE", "Чистая выручка", "RUB",
                        "1000", "900"
                ))
        );
    }

    private String response(String text) {
        return """
                {
                  "schemaVersion": 4,
                  "summary": {
                    "text": "%s",
                    "evidenceRefs": ["STORE.NET_REVENUE"]
                  },
                  "factorExplanations": [],
                  "actionWordings": []
                }
                """.formatted(text);
    }

    private UUID addStore(String name) {
        UUID connectionId = jdbcTemplate.queryForObject(
                """
                SELECT id FROM integration_connections
                WHERE connection_key = 'livesklad-default'
                """,
                UUID.class
        );
        UUID storeId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO stores (
                    id, connection_id, source_system, external_id, name, timezone
                ) VALUES (?, ?, 'LIVESKLAD', ?, ?, 'Europe/Moscow')
                """,
                storeId,
                connectionId,
                "weekly-review-ai-job-" + storeId,
                name
        );
        return storeId;
    }

    private UUID addSnapshot(
            UUID storeId,
            LocalDate periodStart,
            int revision
    ) {
        return addSnapshot(storeId, periodStart, revision, "READY");
    }

    private UUID addSnapshot(
            UUID storeId,
            LocalDate periodStart,
            int revision,
            String reportState
    ) {
        UUID snapshotId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO weekly_review_snapshots (
                    id, store_id, period_start, period_end, timezone, revision,
                    report_contract_version, metrics_policy_version,
                    snapshot_policy_version, quality_policy_version,
                    report_state, report_payload, content_hash
                ) VALUES (
                    ?, ?, ?, ?, 'Europe/Moscow', ?, 2,
                    'metrics-v4', 'snapshot-v7', 'quality-v4',
                    ?, CAST(? AS jsonb), ?
                )
                """,
                snapshotId,
                storeId,
                periodStart,
                periodStart.plusDays(6),
                revision,
                reportState,
                snapshotPayload(snapshotId, periodStart, revision, reportState),
                "a".repeat(64)
        );
        return snapshotId;
    }
}
