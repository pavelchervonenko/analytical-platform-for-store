package com.storeanalytics.interpretation.review.ai;

import static com.storeanalytics.interpretation.review.WeeklyReviewTestPayload.snapshotPayload;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.integration.llm.yandex.LlmProviderFailureKind;
import com.storeanalytics.integration.llm.yandex.LlmProviderOutcomeCertainty;
import com.storeanalytics.integration.llm.yandex.YandexLlmProviderException;
import com.storeanalytics.interpretation.generation.LlmProviderPreflight;
import com.storeanalytics.interpretation.generation.LlmProviderRequest;
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

    private LlmProviderPreflight preflight() {
        return new LlmProviderPreflight(
                1000, 8000, new BigDecimal("3.00"), "RUB"
        );
    }

    private PreparedWeeklyReviewAiRequest prepared(WeeklyReviewAiJob job) {
        WeeklyReviewAiInput input = new WeeklyReviewAiInput(
                WeeklyReviewAiContract.INPUT_SCHEMA_VERSION,
                WeeklyReviewAiContract.PROMPT_VERSION,
                WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION,
                new WeeklyReviewAiInput.SummarySource(
                        "Существенных изменений нет.",
                        "NEUTRAL",
                        List.of("Существенных изменений нет."),
                        List.of("STORE.NET_REVENUE"),
                        List.of()
                ),
                List.of(),
                List.of(),
                List.of(new WeeklyReviewAiInput.EvidenceSource(
                        "STORE.NET_REVENUE",
                        "Чистая выручка",
                        "RUB",
                        "0",
                        "0"
                ))
        );
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
