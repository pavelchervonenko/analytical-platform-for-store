package com.storeanalytics.interpretation.review.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Action;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.ActionTarget;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.AiEnhancement;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.AiState;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.BlockState;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Effect;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Factor;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.GeneratedBy;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.NarrativeItem;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.ReportState;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.SummaryBlock;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Unit;
import com.storeanalytics.interpretation.validation.LlmValidationOutcome;
import com.storeanalytics.interpretation.validation.LlmValidationViolation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class WeeklyReviewAiEnricherTest {

    private static final Instant PUBLISHED_AT =
            Instant.parse("2026-08-27T12:00:00Z");

    private final WeeklyReviewAiEnricher enricher = new WeeklyReviewAiEnricher();

    @Test
    void changesOnlyApprovedWordingAndSupportsLegacySnapshotTitle() {
        WeeklyReviewResponse base = report();

        WeeklyReviewResponse result = enricher.apply(
                base,
                WeeklyReviewAiValidationResult.semanticallyValid(
                        content(), "{\"canonical\":true}"
                ),
                PUBLISHED_AT
        );

        assertThat(result).usingRecursiveComparison()
                .ignoringFields(
                        "summary.outcome.text",
                        "summary.generatedBy",
                        "factors.detail",
                        "actions.generatedBy",
                        "aiEnhancement"
                )
                .isEqualTo(base);
        assertThat(result.summary().outcome().text())
                .isEqualTo("Неделя завершилась ростом чистой выручки.");
        assertThat(result.summary().positive()).isSameAs(base.summary().positive());
        assertThat(result.summary().risk()).isSameAs(base.summary().risk());
        assertThat(result.summary().generatedBy()).isEqualTo(GeneratedBy.AI_ENHANCED);
        assertThat(result.factors()).singleElement().satisfies(factor -> {
            assertThat(factor.detail()).isEqualTo("Возвраты выросли к прошлой неделе.");
            assertThat(factor.comparison()).isSameAs(base.factors().getFirst().comparison());
            assertThat(factor.contributionAmount()).isEqualByComparingTo("-50.00");
        });
        assertThat(result.actions()).singleElement().satisfies(action -> {
            assertThat(action.title()).isEqualTo("Проанализировать рост возвратов");
            assertThat(action.check()).isEqualTo("Сравнить со следующей полной неделей");
            assertThat(action.target()).isSameAs(base.actions().getFirst().target());
            assertThat(action.generatedBy()).isEqualTo(GeneratedBy.AI_ENHANCED);
        });
        assertThat(result.aiEnhancement()).isEqualTo(new AiEnhancement(
                AiState.READY,
                WeeklyReviewAiContract.PROMPT_VERSION,
                WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION,
                PUBLISHED_AT
        ));
        assertThat(result.results()).isSameAs(base.results());
        assertThat(result.revenueDecomposition()).isSameAs(base.revenueDecomposition());
        assertThat(result.salesStructure()).isSameAs(base.salesStructure());
        assertThat(result.team()).isSameAs(base.team());
        assertThat(result.employees()).isSameAs(base.employees());
        assertThat(result.limitations()).isSameAs(base.limitations());
        assertThat(result.evidence()).isSameAs(base.evidence());
        assertThat(result.provenance()).isSameAs(base.provenance());
    }

    @Test
    void preservesLegacyPromptVersionWhenApplyingReadFallback() {
        WeeklyReviewResponse result = enricher.apply(
                report(),
                WeeklyReviewAiValidationResult.semanticallyValid(
                        changedActionTitleContent(), "{\"canonical\":true}"
                ),
                PUBLISHED_AT,
                WeeklyReviewAiContract.LEGACY_PROMPT_VERSION,
                WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION
        );

        assertThat(result.aiEnhancement()).isEqualTo(new AiEnhancement(
                AiState.READY,
                WeeklyReviewAiContract.LEGACY_PROMPT_VERSION,
                WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION,
                PUBLISHED_AT
        ));
        assertThat(result.actions().getFirst().title())
                .isEqualTo("Разобрать причины возвратов");
    }

    @Test
    void rejectsChangedActionTitleForActivePrompt() {
        WeeklyReviewResponse base = report();

        WeeklyReviewResponse result = enricher.apply(
                base,
                WeeklyReviewAiValidationResult.semanticallyValid(
                        changedActionTitleContent(), "{}"
                ),
                PUBLISHED_AT
        );

        assertThat(result).isSameAs(base);
    }


    @Test
    void returnsExactDeterministicReportWhenValidationFailed() {
        WeeklyReviewResponse base = report();
        WeeklyReviewAiValidationResult invalid = WeeklyReviewAiValidationResult.invalid(
                LlmValidationOutcome.SEMANTIC_INVALID,
                List.of(new LlmValidationViolation(
                        "UNAPPROVED_NUMBER", "$.summary", "42"
                ))
        );

        assertThat(enricher.apply(base, invalid, PUBLISHED_AT)).isSameAs(base);
    }

    @Test
    void returnsExactDeterministicReportForMismatchedValidatedObjects() {
        WeeklyReviewResponse base = report();
        WeeklyReviewAiContent mismatched = new WeeklyReviewAiContent(
                4,
                content().summary(),
                List.of(),
                content().actionWordings()
        );

        assertThat(enricher.apply(
                base,
                WeeklyReviewAiValidationResult.semanticallyValid(mismatched, "{}"),
                PUBLISHED_AT
        )).isSameAs(base);
    }

    private WeeklyReviewResponse report() {
        NarrativeItem outcome = new NarrativeItem(
                "summary:outcome",
                "Чистая выручка выросла.",
                Effect.POSITIVE,
                List.of("STORE.NET_REVENUE")
        );
        NarrativeItem positive = new NarrativeItem(
                "summary:positive",
                "Продажи выросли.",
                Effect.POSITIVE,
                List.of("STORE.SALE_REVENUE")
        );
        NarrativeItem risk = new NarrativeItem(
                "summary:risk",
                "Возвраты выросли.",
                Effect.NEGATIVE,
                List.of("STORE.RETURN_REVENUE")
        );
        SummaryBlock summary = new SummaryBlock(
                "summary",
                BlockState.READY,
                outcome,
                positive,
                risk,
                GeneratedBy.DETERMINISTIC
        );
        Factor factor = new Factor(
                "factor:return_revenue",
                "RETURN_CHANGE",
                "Возвраты",
                "Возвраты выросли.",
                mock(WeeklyReviewResponse.MetricComparison.class),
                new BigDecimal("-50.00"),
                Effect.NEGATIVE,
                List.of("STORE.RETURN_REVENUE")
        );
        Action action = new Action(
                "action:restore:return_revenue",
                "HIGH",
                "RESTORE",
                "STORE",
                null,
                "Проанализировать рост возвратов",
                "RETURN_REVENUE",
                new ActionTarget("AT_MOST", new BigDecimal("50.00"), Unit.RUB),
                "Сравнить со следующей полной неделей",
                "NEXT_FULL_WEEK",
                GeneratedBy.DETERMINISTIC,
                List.of("STORE.RETURN_REVENUE")
        );
        return new WeeklyReviewResponse(
                2,
                mock(WeeklyReviewResponse.VersionSet.class),
                mock(WeeklyReviewResponse.PeriodContext.class),
                mock(WeeklyReviewResponse.Provenance.class),
                ReportState.READY,
                mock(WeeklyReviewResponse.QualitySummary.class),
                List.of(),
                summary,
                List.of(
                        mock(WeeklyReviewResponse.MetricComparison.class),
                        mock(WeeklyReviewResponse.MetricComparison.class),
                        mock(WeeklyReviewResponse.MetricComparison.class),
                        mock(WeeklyReviewResponse.MetricComparison.class)
                ),
                mock(WeeklyReviewResponse.RevenueDecomposition.class),
                List.of(factor),
                mock(WeeklyReviewResponse.SalesStructureBlock.class),
                mock(WeeklyReviewResponse.TeamBlock.class),
                List.of(mock(WeeklyReviewResponse.EmployeeCard.class)),
                List.of(action),
                List.of(mock(WeeklyReviewResponse.Limitation.class)),
                List.of(mock(WeeklyReviewResponse.Evidence.class)),
                new AiEnhancement(AiState.DISABLED, null, null, null)
        );
    }

    private WeeklyReviewAiContent changedActionTitleContent() {
        WeeklyReviewAiContent base = content();
        return new WeeklyReviewAiContent(
                base.schemaVersion(),
                base.summary(),
                base.factorExplanations(),
                List.of(new WeeklyReviewAiContent.ActionWording(
                        "action:restore:return_revenue",
                        "Разобрать причины возвратов",
                        "Сравнить со следующей полной неделей"
                ))
        );
    }

    private WeeklyReviewAiContent content() {
        return new WeeklyReviewAiContent(
                4,
                new WeeklyReviewAiContent.Summary(
                        "Неделя завершилась ростом чистой выручки.",
                        List.of("STORE.NET_REVENUE")
                ),
                List.of(new WeeklyReviewAiContent.FactorExplanation(
                        "factor:return_revenue",
                        "Возвраты выросли к прошлой неделе.",
                        List.of("STORE.RETURN_REVENUE")
                )),
                List.of(new WeeklyReviewAiContent.ActionWording(
                        "action:restore:return_revenue",
                        "Проанализировать рост возвратов",
                        "Сравнить со следующей полной неделей"
                ))
        );
    }
}
