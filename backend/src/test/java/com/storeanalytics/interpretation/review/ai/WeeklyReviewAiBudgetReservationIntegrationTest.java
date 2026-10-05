package com.storeanalytics.interpretation.review.ai;

import static com.storeanalytics.interpretation.review.WeeklyReviewTestPayload.snapshotPayload;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.integration.llm.yandex.LlmProviderFailureKind;
import com.storeanalytics.integration.llm.yandex.LlmProviderOutcomeCertainty;
import com.storeanalytics.integration.llm.yandex.YandexLlmProviderException;
import com.storeanalytics.interpretation.generation.LlmProviderPreflight;
import com.storeanalytics.interpretation.generation.LlmProviderRequest;
import com.storeanalytics.interpretation.generation.LlmProviderResponseReceipt;
import com.storeanalytics.interpretation.validation.LlmValidationOutcome;
import com.storeanalytics.interpretation.validation.LlmValidationViolation;
import java.math.BigDecimal;
import java.time.Duration;
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

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class WeeklyReviewAiBudgetReservationIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private WeeklyReviewAiJobStore store;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add(
                "app.interpretation.weekly-review-ai.max-estimated-cost-rub",
                () -> "3.00"
        );
        registry.add(
                "app.interpretation.weekly-review-ai.daily-cost-limit-rub",
                () -> "5.00"
        );
    }


    @Test
    void countsRunningAttemptEstimateBeforeAllowingAnotherProviderCall() {
        WeeklyReviewAiJob first = store.enqueue(
                addSnapshot("budget-reservation-first"),
                "YANDEX",
                "gpt://folder/yandexgpt-5.1",
                2,
                NOW,
                Duration.ofHours(2)
        );
        WeeklyReviewAiJob second = store.enqueue(
                addSnapshot("budget-reservation-second"),
                "YANDEX",
                "gpt://folder/yandexgpt-5.1",
                2,
                NOW.plusMillis(1),
                Duration.ofHours(2)
        );
        WeeklyReviewAiJob firstClaim = store.claimNext(
                "worker-first", Duration.ofMinutes(4), NOW.plusSeconds(1)
        ).orElseThrow();
        assertThat(firstClaim.id()).isEqualTo(first.id());
        store.startAttempt(
                firstClaim,
                "worker-first",
                prepared(firstClaim),
                preflight(),
                NOW.plusSeconds(1)
        );

        WeeklyReviewAiJob secondClaim = store.claimNext(
                "worker-second", Duration.ofMinutes(4), NOW.plusSeconds(2)
        ).orElseThrow();
        assertThat(secondClaim.id()).isEqualTo(second.id());

        assertThatThrownBy(() -> store.startAttempt(
                secondClaim,
                "worker-second",
                prepared(secondClaim),
                preflight(),
                NOW.plusSeconds(2)
        )).isInstanceOf(WeeklyReviewAiBudgetException.class)
                .extracting("code")
                .isEqualTo("DAILY_BUDGET_EXCEEDED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM weekly_review_ai_attempts WHERE job_id IN (?, ?)",
                Long.class,
                first.id(),
                second.id()
        )).isOne();
    }

    @Test
    void keepsEstimateReservedAfterUnknownProviderOutcome() {
        Instant now = NOW.plus(Duration.ofDays(1));
        WeeklyReviewAiJob first = store.enqueue(
                addSnapshot("unknown-outcome-first"),
                "YANDEX",
                "gpt://folder/yandexgpt-5.1",
                2,
                now,
                Duration.ofHours(2)
        );
        WeeklyReviewAiJob second = store.enqueue(
                addSnapshot("unknown-outcome-second"),
                "YANDEX",
                "gpt://folder/yandexgpt-5.1",
                2,
                now.plusMillis(1),
                Duration.ofHours(2)
        );
        WeeklyReviewAiJob firstClaim = store.claimNext(
                "worker-first", Duration.ofMinutes(4), now.plusSeconds(1)
        ).orElseThrow();
        assertThat(firstClaim.id()).isEqualTo(first.id());
        WeeklyReviewAiAttempt attempt = store.startAttempt(
                firstClaim,
                "worker-first",
                prepared(firstClaim),
                preflight(),
                now.plusSeconds(1)
        );
        store.recordProviderFailure(
                firstClaim,
                attempt,
                "worker-first",
                new YandexLlmProviderException(
                        LlmProviderFailureKind.TRANSPORT,
                        LlmProviderOutcomeCertainty.UNKNOWN,
                        "Yandex LLM transport failed",
                        null,
                        null,
                        new java.io.IOException("connection reset")
                ),
                Duration.ofSeconds(30),
                now.plusSeconds(2)
        );

        WeeklyReviewAiJob secondClaim = store.claimNext(
                "worker-second", Duration.ofMinutes(4), now.plusSeconds(3)
        ).orElseThrow();
        assertThat(secondClaim.id()).isEqualTo(second.id());
        assertThatThrownBy(() -> store.startAttempt(
                secondClaim,
                "worker-second",
                prepared(secondClaim),
                preflight(),
                now.plusSeconds(3)
        )).isInstanceOf(WeeklyReviewAiBudgetException.class)
                .extracting("code")
                .isEqualTo("DAILY_BUDGET_EXCEEDED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT provider_outcome FROM weekly_review_ai_attempts WHERE id = ?",
                String.class,
                attempt.id()
        )).isEqualTo("UNKNOWN");
    }

    @Test
    void lateReceiptReplacesEstimateInBudgetWithoutDoubleCountingOrReopeningJob() {
        Instant now = NOW.plus(Duration.ofDays(2));
        store.enqueue(addSnapshot("late-receipt-first"), "YANDEX", "gpt://folder/yandexgpt-5.1",
                2, now, Duration.ofHours(2));
        WeeklyReviewAiJob first = store.claimNext("first", Duration.ofMinutes(4), now).orElseThrow();
        PreparedWeeklyReviewAiRequest request = prepared(first);
        WeeklyReviewAiAttempt attempt = store.startAttempt(first, "first", request, preflight(), now);
        Instant late = now.plusSeconds(241);
        assertThat(store.claimNext("recovery", Duration.ofMinutes(4), late)).isEmpty();
        LlmProviderResponseReceipt response = new LlmProviderResponseReceipt("{}", "model", "late-request",
                1000, 100, 0, 0, 1100, new BigDecimal("2.00"), "RUB", 500L, 200);
        WeeklyReviewAiValidationResult rejected = WeeklyReviewAiValidationResult.invalid(
                LlmValidationOutcome.SEMANTIC_INVALID,
                List.of(new LlmValidationViolation("SYNTHETIC_INVALID", "$", null)));
        store.preserveResponseReceipt(first, attempt, request, response, rejected, late);
        store.preserveResponseReceipt(first, attempt, request, response, rejected, late.plusSeconds(1));
        assertThat(store.actualCostSince(now)).isEqualByComparingTo("2.00");
        assertThat(store.findById(first.id()).orElseThrow().status()).isEqualTo(WeeklyReviewAiJobStatus.FAILED);

        store.enqueue(addSnapshot("late-receipt-second"), "YANDEX", "gpt://folder/yandexgpt-5.1",
                1, late.plusSeconds(2), Duration.ofHours(2));
        WeeklyReviewAiJob second = store.claimNext("second", Duration.ofMinutes(4), late.plusSeconds(2))
                .orElseThrow();
        store.startAttempt(second, "second", prepared(second), preflight(), late.plusSeconds(2));
        store.enqueue(addSnapshot("late-receipt-third"), "YANDEX", "gpt://folder/yandexgpt-5.1",
                1, late.plusSeconds(3), Duration.ofHours(2));
        WeeklyReviewAiJob third = store.claimNext("third", Duration.ofMinutes(4), late.plusSeconds(3))
                .orElseThrow();
        assertThatThrownBy(() -> store.startAttempt(third, "third", prepared(third), preflight(),
                late.plusSeconds(3))).isInstanceOf(WeeklyReviewAiBudgetException.class);
    }

    @Test
    void validatorExecutionFailureIsTerminalEvenWhenAnotherPaidAttemptIsPermitted() {
        Instant now = NOW.plus(Duration.ofDays(3));
        store.enqueue(addSnapshot("validator-crash"), "YANDEX", "gpt://folder/yandexgpt-5.1",
                2, now, Duration.ofHours(2));
        WeeklyReviewAiJob job = store.claimNext("worker", Duration.ofMinutes(4), now).orElseThrow();
        WeeklyReviewAiAttempt attempt = store.startAttempt(job, "worker", prepared(job), preflight(), now);
        WeeklyReviewAiValidationResult failed = WeeklyReviewAiValidationResult.invalid(
                LlmValidationOutcome.SEMANTIC_INVALID,
                List.of(new LlmValidationViolation("VALIDATION_EXECUTION_FAILED", "$", null)));
        store.recordValidationFailure(job, attempt, "worker", new LlmProviderResponseReceipt("{}", "model",
                "validator-request", 1000, 100, 0, 0, 1100, new BigDecimal("2.00"), "RUB", 500L, 200),
                failed, Duration.ofSeconds(30), now.plusSeconds(1));
        WeeklyReviewAiJob saved = store.findById(job.id()).orElseThrow();
        assertThat(saved.status()).isEqualTo(WeeklyReviewAiJobStatus.FAILED);
        assertThat(saved.lastErrorCode()).isEqualTo("VALIDATION_EXECUTION_FAILED");
        assertThat(saved.attemptCount()).isOne();
    }

    @Test
    void lateResponseWithoutKnownPriceKeepsConservativeEstimateReserved() {
        Instant now = NOW.plus(Duration.ofDays(4));
        store.enqueue(addSnapshot("unknown-receipt-first"), "YANDEX", "gpt://folder/yandexgpt-5.1",
                2, now, Duration.ofHours(2));
        WeeklyReviewAiJob first = store.claimNext("first", Duration.ofMinutes(4), now).orElseThrow();
        PreparedWeeklyReviewAiRequest request = prepared(first);
        WeeklyReviewAiAttempt attempt = store.startAttempt(first, "first", request, preflight(), now);
        Instant late = now.plusSeconds(241);
        assertThat(store.claimNext("recovery", Duration.ofMinutes(4), late)).isEmpty();
        LlmProviderResponseReceipt response = new LlmProviderResponseReceipt("{}", "model", "unknown-price",
                null, null, null, null, null, null, null, 500L, 200);
        WeeklyReviewAiValidationResult rejected = WeeklyReviewAiValidationResult.invalid(
                LlmValidationOutcome.SEMANTIC_INVALID,
                List.of(new LlmValidationViolation("SYNTHETIC_INVALID", "$", null)));
        store.preserveResponseReceipt(first, attempt, request, response, rejected, late);
        assertThat(store.actualCostSince(now)).isEqualByComparingTo("0.00");
        store.enqueue(addSnapshot("unknown-receipt-second"), "YANDEX", "gpt://folder/yandexgpt-5.1",
                1, late.plusSeconds(2), Duration.ofHours(2));
        WeeklyReviewAiJob second = store.claimNext("second", Duration.ofMinutes(4), late.plusSeconds(2))
                .orElseThrow();
        assertThatThrownBy(() -> store.startAttempt(second, "second", prepared(second), preflight(),
                late.plusSeconds(2))).isInstanceOf(WeeklyReviewAiBudgetException.class);
    }

    private LlmProviderPreflight preflight() {
        return new LlmProviderPreflight(
                1000, 8000, new BigDecimal("3.00"), "RUB"
        );
    }

    private PreparedWeeklyReviewAiRequest prepared(WeeklyReviewAiJob job) {
        WeeklyReviewAiInput input =
                WeeklyReviewAiTestFixtures.minimalInput("NEUTRAL");
        LlmProviderRequest request = new LlmProviderRequest(
                job.id(),
                job.providerCode(),
                job.requestedModel(),
                "system",
                "{\"contractVersion\":2}",
                "{}",
                new BigDecimal("0.1"),
                1400,
                NOW.plusSeconds(180)
        );
        return new PreparedWeeklyReviewAiRequest(
                request, "a".repeat(64), input, "b".repeat(64)
        );
    }

    private UUID addSnapshot(String suffix) {
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
                "weekly-review-ai-" + suffix,
                suffix
        );
        UUID snapshotId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO weekly_review_snapshots (
                    id, store_id, period_start, period_end, timezone, revision,
                    report_contract_version, metrics_policy_version,
                    snapshot_policy_version, quality_policy_version,
                    report_state, report_payload, content_hash
                ) VALUES (
                    ?, ?, ?, ?, 'Europe/Moscow', 1, 2,
                    'metrics-v4', 'snapshot-v7', 'quality-v4',
                    'READY', CAST(? AS jsonb), ?
                )
                """,
                snapshotId,
                storeId,
                LocalDate.of(2026, 8, 17),
                LocalDate.of(2026, 8, 23),
                snapshotPayload(snapshotId, LocalDate.of(2026, 8, 17), 1, "READY"),
                "a".repeat(64)
        );
        return snapshotId;
    }
}
