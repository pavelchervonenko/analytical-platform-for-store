package com.storeanalytics.interpretation.review.ai;

import static com.storeanalytics.interpretation.review.WeeklyReviewTestPayload.snapshotPayload;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
class WeeklyReviewAiEnrichmentStoreIntegrationTest {

    private static final Instant VALIDATED_AT =
            Instant.parse("2026-08-27T11:59:00Z");
    private static final Instant PUBLISHED_AT =
            Instant.parse("2026-08-27T12:00:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private WeeklyReviewAiEnrichmentStore store;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WeeklyReviewAiContentCodec codec;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void storesOneImmutableValidatedEnrichmentPerSnapshotAndVersion() {
        UUID snapshotId = addSnapshot();
        WeeklyReviewAiInput input = input();
        String responseBody = WeeklyReviewAiTestFixtures.outcomeSelection();
        WeeklyReviewAiValidationResult structural =
                new WeeklyReviewAiStructuralValidator().validate(responseBody);
        assertThatThrownBy(() -> store.persist(
                snapshotId,
                input,
                structural,
                VALIDATED_AT,
                PUBLISHED_AT
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("semantically valid");

        WeeklyReviewAiValidationResult validation =
                new WeeklyReviewAiSemanticValidator(
                        new WeeklyReviewAiStructuralValidator()
                ).validate(input, responseBody);
        PersistedWeeklyReviewAiEnrichment first = store.persist(
                snapshotId,
                input,
                validation,
                VALIDATED_AT,
                PUBLISHED_AT
        );
        PersistedWeeklyReviewAiEnrichment reused = store.persist(
                snapshotId,
                input,
                validation,
                VALIDATED_AT.plusSeconds(10),
                PUBLISHED_AT.plusSeconds(10)
        );

        assertThat(reused.id()).isEqualTo(first.id());
        assertThat(first.snapshotId()).isEqualTo(snapshotId);
        assertThat(first.promptVersion())
                .isEqualTo(WeeklyReviewAiContract.PROMPT_VERSION);
        assertThat(first.contentSchemaVersion()).isEqualTo(4);
        assertThat(first.inputHash()).hasSize(64);
        assertThat(first.contentHash()).hasSize(64);
        assertThat(store.findPublished(snapshotId, PUBLISHED_AT)).contains(first);
        assertThat(store.findPublished(
                snapshotId, PUBLISHED_AT.minusMillis(1)
        )).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM weekly_review_ai_enrichments
                WHERE snapshot_id = ?
                """,
                Long.class,
                snapshotId
        )).isOne();

        assertThatThrownBy(() -> store.persist(
                snapshotId,
                input,
                differentValidation(),
                VALIDATED_AT,
                PUBLISHED_AT
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different content");

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                UPDATE weekly_review_ai_enrichments
                SET published_at = published_at + interval '1 second'
                WHERE id = ?
                """,
                first.id()
        )).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(
                        "Weekly review AI enrichments are immutable"
                );
    }

    @Test
    void validatesPersistsReadsAndAppliesV25RiskEndToEnd() {
        UUID snapshotId = addSnapshot();
        WeeklyReviewAiInput input = riskInputMatchingReport();
        WeeklyReviewAiValidationResult validation =
                new WeeklyReviewAiSemanticValidator(
                        new WeeklyReviewAiStructuralValidator()
                ).validate(
                        input, WeeklyReviewAiTestFixtures.returnRiskSelection()
                );
        assertThat(validation.semanticValidated()).isTrue();

        store.persist(
                snapshotId, input, validation, VALIDATED_AT, PUBLISHED_AT
        );
        PersistedWeeklyReviewAiEnrichment loaded = store.findPublished(
                snapshotId, PUBLISHED_AT
        ).orElseThrow();
        var enriched = new WeeklyReviewAiEnricher().apply(
                WeeklyReviewAiEnricherTest.report(),
                loaded.validationResult(),
                loaded.publishedAt(),
                loaded.promptVersion(),
                loaded.contentSchemaVersion()
        );

        assertThat(enriched.aiEnhancement().promptVersion())
                .isEqualTo(WeeklyReviewAiContract.PROMPT_VERSION);
        assertThat(enriched.summary().outcome().text())
                .contains("Главная зона внимания")
                .contains("давление возвратов");
        assertThat(enriched.summary().outcome().evidenceRefs())
                .containsExactly(
                        "STORE.NET_REVENUE",
                        "STORE.RETURN_REVENUE"
                );
        assertThat(enriched.factors().getFirst().detail())
                .contains("отдельная зона контроля");
        assertThat(enriched.actions().getFirst().title())
                .isEqualTo("Проанализировать рост возвратов");
    }

    @Test
    void readsV25ThenV24ThenV23ThenV22() {
        UUID snapshotId = addSnapshot();
        WeeklyReviewAiContent legacyContent = new WeeklyReviewAiContent(
                4,
                new WeeklyReviewAiContent.Summary(
                        "Проверенный итог v22",
                        List.of("STORE.NET_REVENUE")
                ),
                List.of(),
                List.of()
        );
        insertEnrichment(
                snapshotId, WeeklyReviewAiContract.LEGACY_PROMPT_VERSION,
                legacyContent, VALIDATED_AT, PUBLISHED_AT
        );

        PersistedWeeklyReviewAiEnrichment fallback = store.findPublished(
                snapshotId, PUBLISHED_AT
        ).orElseThrow();
        assertThat(fallback.promptVersion())
                .isEqualTo(WeeklyReviewAiContract.LEGACY_PROMPT_VERSION);
        assertThat(fallback.content()).isEqualTo(legacyContent);

        WeeklyReviewAiContent v23Content = new WeeklyReviewAiContent(
                4,
                new WeeklyReviewAiContent.Summary(
                        "Проверенный итог v23",
                        List.of("STORE.NET_REVENUE")
                ),
                List.of(),
                List.of()
        );
        insertEnrichment(
                snapshotId, WeeklyReviewAiContract.V23_PROMPT_VERSION,
                v23Content, VALIDATED_AT.plusSeconds(1),
                PUBLISHED_AT.plusSeconds(1)
        );
        assertThat(store.findPublished(
                snapshotId, PUBLISHED_AT.plusSeconds(1)
        ).orElseThrow().promptVersion()).isEqualTo(
                WeeklyReviewAiContract.V23_PROMPT_VERSION
        );

        WeeklyReviewAiContent v24Content = new WeeklyReviewAiContent(
                4,
                new WeeklyReviewAiContent.Summary(
                        "Проверенный итог v24",
                        List.of("STORE.NET_REVENUE")
                ),
                List.of(),
                List.of()
        );
        insertEnrichment(
                snapshotId, WeeklyReviewAiContract.PREVIOUS_PROMPT_VERSION,
                v24Content, VALIDATED_AT.plusSeconds(2),
                PUBLISHED_AT.plusSeconds(2)
        );
        assertThat(store.findPublished(
                snapshotId, PUBLISHED_AT.plusSeconds(2)
        ).orElseThrow().promptVersion()).isEqualTo(
                WeeklyReviewAiContract.PREVIOUS_PROMPT_VERSION
        );

        WeeklyReviewAiInput activeInput = input();
        PersistedWeeklyReviewAiEnrichment active = store.persist(
                snapshotId,
                activeInput,
                semanticValidation(activeInput),
                VALIDATED_AT.plusSeconds(3),
                PUBLISHED_AT.plusSeconds(3)
        );

        assertThat(store.findPublished(
                snapshotId, PUBLISHED_AT.plusSeconds(3)
        )).contains(active);
    }

    @Test
    void skipsCorruptedV25AndReadsValidV24Fallback() {
        UUID snapshotId = addSnapshot();
        WeeklyReviewAiContent v24Content = new WeeklyReviewAiContent(
                4,
                new WeeklyReviewAiContent.Summary(
                        "Проверенный fallback v24",
                        List.of("STORE.NET_REVENUE")
                ),
                List.of(),
                List.of()
        );
        insertEnrichment(
                snapshotId, WeeklyReviewAiContract.PREVIOUS_PROMPT_VERSION,
                v24Content, VALIDATED_AT, PUBLISHED_AT
        );
        WeeklyReviewAiContent v25Content = new WeeklyReviewAiContent(
                4,
                new WeeklyReviewAiContent.Summary(
                        "Повреждённый v25",
                        List.of("STORE.NET_REVENUE")
                ),
                List.of(),
                List.of()
        );
        String canonical = codec.canonical(v25Content);
        jdbcTemplate.update(
                """
                INSERT INTO weekly_review_ai_enrichments (
                    id, snapshot_id, prompt_version, content_schema_version,
                    input_hash, content_payload, content_hash,
                    validated_at, published_at
                ) VALUES (?, ?, ?, 4, ?, CAST(? AS jsonb), ?, ?, ?)
                """,
                UUID.randomUUID(),
                snapshotId,
                WeeklyReviewAiContract.PROMPT_VERSION,
                "e".repeat(64),
                canonical,
                "f".repeat(64),
                java.sql.Timestamp.from(VALIDATED_AT.plusSeconds(1)),
                java.sql.Timestamp.from(PUBLISHED_AT.plusSeconds(1))
        );

        List<PersistedWeeklyReviewAiEnrichment> candidates =
                store.findPublishedCandidates(
                        snapshotId, PUBLISHED_AT.plusSeconds(1)
                );

        assertThat(candidates).singleElement().satisfies(value -> {
            assertThat(value.promptVersion()).isEqualTo(
                    WeeklyReviewAiContract.PREVIOUS_PROMPT_VERSION
            );
            assertThat(value.content()).isEqualTo(v24Content);
        });
        assertThat(store.findPublished(
                snapshotId, PUBLISHED_AT.plusSeconds(1)
        )).map(PersistedWeeklyReviewAiEnrichment::promptVersion)
                .contains(WeeklyReviewAiContract.PREVIOUS_PROMPT_VERSION);
    }

    @Test
    void rejectsContentWithoutRequiredSchemaVersion() {
        UUID snapshotId = addSnapshot();

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO weekly_review_ai_enrichments (
                    id, snapshot_id, prompt_version, content_schema_version,
                    input_hash, content_payload, content_hash,
                    validated_at, published_at
                ) VALUES (
                    ?, ?, 'weekly-interpretation-v24', 4, ?,
                    CAST('{}' AS jsonb), ?, ?, ?
                )
                """,
                UUID.randomUUID(),
                snapshotId,
                "a".repeat(64),
                "b".repeat(64),
                java.sql.Timestamp.from(VALIDATED_AT),
                java.sql.Timestamp.from(PUBLISHED_AT)
        )).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(
                        "ck_weekly_review_ai_enrichment_schema"
                );
    }

    private void insertEnrichment(
            UUID snapshotId,
            String promptVersion,
            WeeklyReviewAiContent content,
            Instant validatedAt,
            Instant publishedAt
    ) {
        String canonical = codec.canonical(content);
        jdbcTemplate.update(
                """
                INSERT INTO weekly_review_ai_enrichments (
                    id, snapshot_id, prompt_version, content_schema_version,
                    input_hash, content_payload, content_hash,
                    validated_at, published_at
                ) VALUES (?, ?, ?, 4, ?, CAST(? AS jsonb), ?, ?, ?)
                """,
                UUID.randomUUID(),
                snapshotId,
                promptVersion,
                "d".repeat(64),
                canonical,
                codec.hash(canonical),
                java.sql.Timestamp.from(validatedAt),
                java.sql.Timestamp.from(publishedAt)
        );
    }

    private WeeklyReviewAiInput riskInputMatchingReport() {
        return new WeeklyReviewAiInput(
                4,
                WeeklyReviewAiContract.PROMPT_VERSION,
                4,
                "READY",
                new WeeklyReviewAiInput.SummarySource(
                        "POSITIVE",
                        List.of("SUMMARY_RISK"),
                        List.of("factor:return_revenue"),
                        List.of("STORE.NET_REVENUE")
                ),
                List.of(new WeeklyReviewAiInput.FactorSource(
                        "factor:return_revenue",
                        "RETURN_CHANGE",
                        "Возвраты",
                        "UP",
                        "NEGATIVE",
                        true,
                        List.of("FACTOR_RISK", "FACTOR_CONTROL"),
                        List.of("STORE.RETURN_REVENUE")
                )),
                List.of(new WeeklyReviewAiInput.ActionSource(
                        "action:restore:return_revenue",
                        "Проанализировать рост возвратов",
                        "Сравнить со следующей полной неделей",
                        List.of("STORE.RETURN_REVENUE")
                )),
                List.of(
                        WeeklyReviewAiTestFixtures.evidence(
                                "STORE.NET_REVENUE",
                                "Чистая выручка",
                                "RUB",
                                "120000",
                                "113315"
                        ),
                        WeeklyReviewAiTestFixtures.evidence(
                                "STORE.RETURN_REVENUE",
                                "Возвраты",
                                "RUB",
                                "15000",
                                "8000"
                        )
                )
        );
    }

    private WeeklyReviewAiInput input() {
        return WeeklyReviewAiTestFixtures.minimalInput("POSITIVE");
    }

    private WeeklyReviewAiValidationResult semanticValidation(
            WeeklyReviewAiInput input
    ) {
        return new WeeklyReviewAiSemanticValidator(
                new WeeklyReviewAiStructuralValidator()
        ).validate(input, WeeklyReviewAiTestFixtures.outcomeSelection());
    }

    private WeeklyReviewAiValidationResult differentValidation() {
        WeeklyReviewAiContent content = new WeeklyReviewAiContent(
                4,
                new WeeklyReviewAiContent.Summary(
                        "Другой проверенный итог",
                        List.of("STORE.NET_REVENUE")
                ),
                List.of(),
                List.of()
        );
        return WeeklyReviewAiValidationResult.semanticallyValid(
                content, codec.canonical(content)
        );
    }

    private UUID addSnapshot() {
        UUID connectionId = jdbcTemplate.queryForObject(
                """
                SELECT id
                FROM integration_connections
                WHERE connection_key = 'livesklad-default'
                """,
                UUID.class
        );
        UUID storeId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO stores (
                    id, connection_id, source_system, external_id, name, timezone
                ) VALUES (?, ?, 'LIVESKLAD', ?, 'AI enrichment store',
                    'Europe/Moscow')
                """,
                storeId,
                connectionId,
                "weekly-review-ai-" + storeId
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
