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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class WeeklyReviewAiCompletionServiceIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");
    private static final String OWNER = "completion-worker";

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private WeeklyReviewAiCompletionService completionService;

    @Autowired
    private WeeklyReviewAiJobStore jobStore;

    @Autowired
    private WeeklyReviewAiEnrichmentStore enrichmentStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void rollsBackPublicationButPreservesReceiptWhenJobCompletionLosesItsLease() {
        BigDecimal previousCost = jobStore.actualCostSince(NOW.minusSeconds(1));
        UUID snapshotId = addSnapshot();
        WeeklyReviewAiJob pending = jobStore.enqueue(
                snapshotId,
                "YANDEX",
                "gpt://folder/yandexgpt-5.1",
                2,
                NOW,
                Duration.ofHours(2)
        );
        WeeklyReviewAiJob claimed = jobStore.claimNext(
                OWNER, Duration.ofMinutes(4), NOW
        ).orElseThrow();
        PreparedWeeklyReviewAiRequest prepared = prepared(claimed);
        WeeklyReviewAiAttempt attempt = jobStore.startAttempt(
                claimed,
                OWNER,
                prepared,
                new LlmProviderPreflight(
                        1000, 8000, new BigDecimal("3.00"), "RUB"
                ),
                NOW
        );
        LlmProviderResponseReceipt receipt = receipt();
        WeeklyReviewAiValidationResult validation = validation(prepared.input());

        assertThatThrownBy(() -> completionService.complete(
                claimed,
                attempt,
                "different-worker",
                prepared,
                receipt,
                validation,
                NOW.plusSeconds(1)
        )).isInstanceOf(WeeklyReviewAiLeaseLostException.class);

        assertThat(enrichmentStore.findPublished(
                snapshotId, NOW.plusSeconds(10)
        )).isEmpty();
        assertThat(jobStore.findById(pending.id()).orElseThrow().status())
                .isEqualTo(WeeklyReviewAiJobStatus.RUNNING);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM weekly_review_ai_attempts WHERE id = ?",
                String.class,
                attempt.id()
        )).isEqualTo("STARTED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT actual_cost FROM weekly_review_ai_response_receipts WHERE attempt_id = ?",
                BigDecimal.class, attempt.id())).isEqualByComparingTo("2.00");

        completionService.complete(
                claimed,
                attempt,
                OWNER,
                prepared,
                receipt,
                validation,
                NOW.plusSeconds(2)
        );

        PersistedWeeklyReviewAiEnrichment enrichment = enrichmentStore
                .findPublished(snapshotId, NOW.plusSeconds(10))
                .orElseThrow();
        assertThat(enrichment.content().summary().text())
                .isEqualTo("Неделя завершилась лучше периода сравнения.");
        assertThat(enrichment.canonicalContent())
                .doesNotContain("SUMMARY_", "FACTOR_");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT response_payload FROM weekly_review_ai_attempts "
                        + "WHERE id = ?",
                String.class,
                attempt.id()
        )).contains("SUMMARY_OUTCOME");
        assertThat(jobStore.findById(pending.id()).orElseThrow().status())
                .isEqualTo(WeeklyReviewAiJobStatus.SUCCEEDED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM weekly_review_ai_attempts WHERE id = ?",
                String.class,
                attempt.id()
        )).isEqualTo("SUCCEEDED");
        assertThat(jobStore.actualCostSince(NOW.minusSeconds(1)))
                .isEqualByComparingTo(previousCost.add(new BigDecimal("2.00")));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM weekly_review_ai_response_receipts WHERE attempt_id = ?",
                Long.class, attempt.id())).isOne();
    }

    @Test
    void expiredLeaseCannotPublishAndLaterRecoveryCannotEraseReceiptOrPermitRetry() {
        Started started = start();
        BigDecimal before = jobStore.actualCostSince(NOW.minusSeconds(1));
        Instant late = NOW.plus(Duration.ofMinutes(4));
        assertThatThrownBy(() -> complete(started, late)).isInstanceOf(WeeklyReviewAiLeaseLostException.class);
        assertThat(enrichmentStore.findPublished(started.job().snapshotId(), late)).isEmpty();
        assertThat(jobStore.actualCostSince(NOW.minusSeconds(1)))
                .isEqualByComparingTo(before.add(new BigDecimal("2.00")));
        assertThat(jobStore.claimNext("recovery", Duration.ofMinutes(4), late.plusSeconds(1))).isEmpty();
        assertThat(jobStore.findById(started.job().id()).orElseThrow().status())
                .isEqualTo(WeeklyReviewAiJobStatus.FAILED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT provider_outcome FROM weekly_review_ai_attempts WHERE id = ?",
                String.class, started.attempt().id())).isEqualTo("UNKNOWN");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM weekly_review_ai_response_receipts WHERE attempt_id = ?",
                Long.class, started.attempt().id())).isOne();
    }

    @Test
    void responseAfterRecoveryIsDurableWithoutRewritingFinalAttemptAndIsCountedOnce() {
        Started started = start();
        Instant late = NOW.plus(Duration.ofMinutes(5));
        assertThat(jobStore.claimNext("recovery", Duration.ofMinutes(4), late)).isEmpty();
        BigDecimal before = jobStore.actualCostSince(NOW.minusSeconds(1));
        assertThatThrownBy(() -> complete(started, late.plusSeconds(1)))
                .isInstanceOf(WeeklyReviewAiLeaseLostException.class);
        jobStore.preserveResponseReceipt(started.job(), started.attempt(), started.prepared(),
                receipt(), validation(started.prepared().input()), late.plusSeconds(2));
        assertThat(jobStore.actualCostSince(NOW.minusSeconds(1)))
                .isEqualByComparingTo(before.add(new BigDecimal("2.00")));
        assertThat(enrichmentStore.findPublished(started.job().snapshotId(), late)).isEmpty();
        assertThat(jobStore.findById(started.job().id()).orElseThrow().status())
                .isEqualTo(WeeklyReviewAiJobStatus.FAILED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT provider_outcome FROM weekly_review_ai_attempts WHERE id = ?",
                String.class, started.attempt().id())).isEqualTo("UNKNOWN");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT received_at FROM weekly_review_ai_response_receipts WHERE attempt_id = ?",
                java.sql.Timestamp.class, started.attempt().id()).toInstant()).isEqualTo(late.plusSeconds(1));
    }

    @Test
    void receiptIsImmutableAndConflictingMetadataOrIdentityCannotReplaceIt() {
        Started started = start();
        WeeklyReviewAiValidationResult valid = validation(started.prepared().input());
        jobStore.preserveResponseReceipt(started.job(), started.attempt(), started.prepared(), receipt(), valid, NOW);
        LlmProviderResponseReceipt changed = new LlmProviderResponseReceipt(responseBody(),
                "gpt://folder/yandexgpt-5.1", "completion-request", 1000, 100, 0, 0, 1100,
                new BigDecimal("9.00"), "RUB", 500L, 200);
        assertThatThrownBy(() -> jobStore.preserveResponseReceipt(started.job(), started.attempt(),
                started.prepared(), changed, valid, NOW)).isInstanceOf(IllegalArgumentException.class);
        PreparedWeeklyReviewAiRequest wrongRequest = new PreparedWeeklyReviewAiRequest(
                started.prepared().request(), "c".repeat(64), started.prepared().input(), "b".repeat(64));
        assertThatThrownBy(() -> jobStore.preserveResponseReceipt(started.job(), started.attempt(),
                wrongRequest, receipt(), valid, NOW)).isInstanceOf(IllegalArgumentException.class);
        WeeklyReviewAiAttempt wrongNumber = new WeeklyReviewAiAttempt(
                started.attempt().id(), started.job().id(), 2, NOW);
        assertThatThrownBy(() -> jobStore.preserveResponseReceipt(started.job(), wrongNumber,
                started.prepared(), receipt(), valid, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE weekly_review_ai_response_receipts SET actual_cost = 9 WHERE attempt_id = ?",
                started.attempt().id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM weekly_review_ai_response_receipts WHERE attempt_id = ?", started.attempt().id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT actual_cost FROM weekly_review_ai_response_receipts WHERE attempt_id = ?",
                BigDecimal.class, started.attempt().id())).isEqualByComparingTo("2.00");
    }

    private Started start() {
        jobStore.enqueue(addSnapshot(), "YANDEX", "gpt://folder/yandexgpt-5.1", 2, NOW, Duration.ofHours(2));
        WeeklyReviewAiJob job = jobStore.claimNext(OWNER, Duration.ofMinutes(4), NOW).orElseThrow();
        PreparedWeeklyReviewAiRequest request = prepared(job);
        WeeklyReviewAiAttempt attempt = jobStore.startAttempt(job, OWNER, request,
                new LlmProviderPreflight(1000, 8000, new BigDecimal("3.00"), "RUB"), NOW);
        return new Started(job, attempt, request);
    }

    @Test
    void independentReceiptCannotOverrideAnAlreadyKnownLegacyResponseOrPrice() {
        Started started = start();
        WeeklyReviewAiValidationResult valid = validation(started.prepared().input());
        jobStore.recordSuccessfulAttempt(started.job(), started.attempt(), OWNER, receipt(), valid, NOW);
        LlmProviderResponseReceipt changed = new LlmProviderResponseReceipt(responseBody(),
                "gpt://folder/yandexgpt-5.1", "completion-request", 1000, 100, 0, 0, 1100,
                new BigDecimal("9.00"), "RUB", 500L, 200);
        assertThatThrownBy(() -> jobStore.preserveResponseReceipt(started.job(), started.attempt(),
                started.prepared(), changed, valid, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM weekly_review_ai_response_receipts WHERE attempt_id = ?",
                Long.class, started.attempt().id())).isZero();
        jobStore.preserveResponseReceipt(started.job(), started.attempt(), started.prepared(), receipt(), valid, NOW);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT actual_cost FROM weekly_review_ai_attempts WHERE id = ?",
                BigDecimal.class, started.attempt().id())).isEqualByComparingTo("2.00");
    }

    private void complete(Started started, Instant now) {
        completionService.complete(started.job(), started.attempt(), OWNER, started.prepared(),
                receipt(), validation(started.prepared().input()), now);
    }

    private record Started(WeeklyReviewAiJob job, WeeklyReviewAiAttempt attempt,
                           PreparedWeeklyReviewAiRequest prepared) {
    }

    private PreparedWeeklyReviewAiRequest prepared(WeeklyReviewAiJob job) {
        WeeklyReviewAiInput input =
                WeeklyReviewAiTestFixtures.minimalInput("POSITIVE");
        LlmProviderRequest request = new LlmProviderRequest(
                job.id(),
                job.providerCode(),
                job.requestedModel(),
                "system",
                "{\"contractVersion\":3}",
                "{}",
                new BigDecimal("0.1"),
                1400,
                NOW.plusSeconds(180)
        );
        return new PreparedWeeklyReviewAiRequest(
                request, "a".repeat(64), input, "b".repeat(64)
        );
    }

    private WeeklyReviewAiValidationResult validation(
            WeeklyReviewAiEditorialInput input
    ) {
        return new WeeklyReviewAiSemanticValidator(
                new WeeklyReviewAiStructuralValidator()
        ).validate(input, responseBody());
    }

    private LlmProviderResponseReceipt receipt() {
        return new LlmProviderResponseReceipt(
                responseBody(),
                "gpt://folder/yandexgpt-5.1",
                "completion-request",
                1000,
                100,
                0,
                0,
                1100,
                new BigDecimal("2.00"),
                "RUB",
                500L,
                200
        );
    }

    private String responseBody() {
        return WeeklyReviewAiTestFixtures.outcomeSelection();
    }

    private UUID addSnapshot() {
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
                "weekly-review-ai-completion-" + storeId,
                "AI completion transaction"
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
