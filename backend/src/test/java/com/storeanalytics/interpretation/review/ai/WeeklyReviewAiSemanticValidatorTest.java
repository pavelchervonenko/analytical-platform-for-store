package com.storeanalytics.interpretation.review.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.interpretation.validation.LlmValidationOutcome;
import java.util.List;
import org.junit.jupiter.api.Test;

class WeeklyReviewAiSemanticValidatorTest {

    private final WeeklyReviewAiSemanticValidator validator =
            new WeeklyReviewAiSemanticValidator(
                    new WeeklyReviewAiStructuralValidator()
            );

    @Test
    void acceptsExactStoreOnlyEnrichment() {
        WeeklyReviewAiValidationResult result = validator.validate(
                input(false), validResponse()
        );

        assertThat(result.outcome()).isEqualTo(LlmValidationOutcome.VALID);
        assertThat(result.content()).isNotNull();
        assertThat(result.semanticValidated()).isTrue();
    }

    @Test
    void rejectsAddedRemovedOrReorderedBackendObjects() {
        assertViolation("""
                {
                  "schemaVersion": 4,
                  "summary": {
                    "text": "Неделя сильнее предыдущей: выручка выросла, возвраты требуют внимания.",
                    "evidenceRefs": ["STORE.NET_REVENUE", "STORE.RETURN_REVENUE"]
                  },
                  "factorExplanations": [],
                  "actionWordings": [
                    {
                      "actionId": "action:restore:return_revenue",
                      "title": "Разобрать рост возвратов",
                      "check": "Сравнить со следующей полной неделей"
                    }
                  ]
                }
                """, "FACTOR_SET_MISMATCH");

        assertViolation(validResponse().replace(
                "action:restore:return_revenue",
                "action:invented"
        ), "ACTION_SET_MISMATCH");
    }

    @Test
    void rejectsEvidenceOutsideObjectAllowlist() {
        assertViolation(validResponse().replace(
                "\"evidenceRefs\": [\"STORE.RETURN_REVENUE\"]",
                "\"evidenceRefs\": [\"STORE.NET_REVENUE\"]"
        ), "FACTOR_EVIDENCE_MISMATCH");
    }

    @Test
    void rejectsNewNumbersAndUnapprovedCausalLanguage() {
        assertViolation(validResponse().replace(
                "Возвраты уменьшили результат продаж сильнее, чем в периоде сравнения — это зона внимания.",
                "Возвраты выросли на 12% относительно предыдущей недели."
        ), "UNAPPROVED_NUMBER");

        assertViolation(validResponse().replace(
                "Возвраты уменьшили результат продаж сильнее, чем в периоде сравнения — это зона внимания.",
                "Выручка изменилась из-за роста возвратов; это зона внимания."
        ), "UNAPPROVED_CAUSALITY");

        WeeklyReviewAiValidationResult causal = validator.validate(
                input(true),
                validResponse()
        );
        assertThat(causal.outcome()).isEqualTo(LlmValidationOutcome.VALID);
    }

    @Test
    void returnEvidenceDoesNotAuthorizeGeneralRevenueClaims() {
        assertViolation(validResponse().replace(
                "Возвраты уменьшили результат продаж сильнее, чем в периоде "
                        + "сравнения — это зона внимания.",
                "Возвраты уменьшили результат продаж сильнее, чем в периоде "
                        + "сравнения; общая выручка — зона внимания."
        ), "UNAPPROVED_METRIC");
    }

    @Test
    void rejectsPlanAndDuplicateNarrative() {
        assertViolation(validResponse().replace(
                "Неделя сильнее предыдущей: выручка выросла, возвраты требуют внимания.",
                "План месяца требует внимания."
        ), "FORBIDDEN_HORIZON");

        assertViolation(validResponse().replace(
                "Разобрать рост возвратов",
                "Возвраты уменьшили результат продаж сильнее, чем в периоде сравнения — это зона внимания."
        ), "DUPLICATE_NARRATIVE");
    }

    @Test
    void rejectsManagementContractViolations() {
        assertViolation(validResponse().replace(
                "Неделя сильнее", "Неделя слабее"
        ), "SUMMARY_NARRATIVE_CHANGED");
        assertViolation(validResponse().replace(
                "Неделя сильнее предыдущей: выручка выросла, возвраты требуют внимания.",
                "Выручка выросла, возвраты требуют внимания."
        ), "SUMMARY_NARRATIVE_CHANGED");
        assertViolation(validResponse().replace(
                "Возвраты уменьшили результат продаж сильнее, чем в периоде "
                                + "сравнения — это зона внимания.",
                "Возвраты выросли относительно предыдущей недели."
        ), "SOURCE_NARRATIVE_RESTATED");
        assertViolation(validResponse().replace(
                "Разобрать рост возвратов", "Проверить"
        ), "ACTION_TITLE_WORD_COUNT");
        assertViolation(validResponse().replace(
                "Возвраты уменьшили результат продаж сильнее, чем в периоде "
                        + "сравнения — это зона внимания.",
                "Возвраты уменьшили результат продаж сильнее, чем в периоде сравнения."
        ), "FACTOR_EFFECT_MISSING");
        assertViolation(validResponse().replace(
                "Разобрать рост возвратов", "Восстановить уровень возвратов"
        ), "DESIRED_OUTCOME_ACTION");

        assertViolation(validResponse().replace(
                "Неделя сильнее", "Неделя сильнее не стала"
        ), "SUMMARY_NARRATIVE_CHANGED");
        assertViolation(validResponse().replace(
                "это зона внимания.",
                "зоной внимания это не является."
        ), "FACTOR_EFFECT_MISSING");
        assertViolation(validResponse().replace(
                "Возвраты уменьшили результат продаж",
                "Нельзя с достаточной уверенностью утверждать, что "
                        + "Возвраты уменьшили результат продаж"
        ), "MANAGEMENT_MEANING_MISSING");

        WeeklyReviewAiValidationResult positive = validator.validate(
                input(false, "POSITIVE"),
                validResponse().replace(
                        "Возвраты уменьшили результат продаж сильнее, чем в периоде "
                                + "сравнения — это зона внимания.",
                        "Возвраты снизились; это зона внимания."
                )
        );
        assertThat(positive.violations())
                .extracting(value -> value.code())
                .contains("FACTOR_EFFECT_CONTRADICTION");
    }

    @Test
    void rejectsMissingManagementMeaningAndChangedAction() {
        assertViolation(validResponse().replace(
                "Возвраты уменьшили результат продаж сильнее, чем в периоде "
                        + "сравнения — это зона внимания.",
                "Возвраты снизились; это зона внимания."
        ), "MANAGEMENT_MEANING_MISSING");

        assertViolation(validResponse().replace(
                "Разобрать рост возвратов",
                "Оформить возврат клиенту"
        ), "ACTION_TITLE_CHANGED");
    }

    @Test
    void mixedSummaryRejectsWholeWeekDirection() {
        WeeklyReviewAiValidationResult contradiction = validator.validate(
                input(false, "NEGATIVE", "MIXED"),
                validResponse()
        );
        assertThat(contradiction.violations())
                .extracting(value -> value.code())
                .contains("SUMMARY_NARRATIVE_CHANGED");

        WeeklyReviewAiValidationResult valid = validator.validate(
                input(false, "NEGATIVE", "MIXED"),
                validResponse().replace(
                        "Неделя сильнее предыдущей: выручка выросла, возвраты требуют внимания.",
                        "Картина недели неоднозначная: выручка выросла, "
                                + "возвраты требуют внимания."
                )
        );
        assertThat(valid.outcome()).isEqualTo(LlmValidationOutcome.VALID);
    }

    @Test
    void rejectsNegatedManagementSignals() {
        assertViolation(validResponse().replace(
                "Неделя сильнее", "Неделя не сильнее"
        ), "SUMMARY_NARRATIVE_CHANGED");
        assertViolation(validResponse().replace(
                "это зона внимания.", "это не зона внимания."
        ), "FACTOR_EFFECT_MISSING");
        assertViolation(validResponse().replace(
                "Возвраты уменьшили результат продаж",
                "Неверно, что Возвраты уменьшили результат продаж"
        ), "MANAGEMENT_MEANING_MISSING");

        assertViolation(validResponse().replace(
                "Неделя сильнее предыдущей",
                "Неделя может считаться сильнее предыдущей"
        ), "SUMMARY_NARRATIVE_CHANGED");
        assertViolation(validResponse().replace(
                "Неделя сильнее предыдущей: выручка выросла, "
                        + "возвраты требуют внимания.",
                "Неделя сильнее предыдущей: выручка выросла, "
                        + "хотя это спорно."
        ), "SUMMARY_NARRATIVE_CHANGED");
        assertViolation(validResponse().replace(
                "это зона внимания.",
                "это может считаться зоной внимания."
        ), "FACTOR_EFFECT_MISSING");
        assertViolation(validResponse().replace(
                "это зона внимания.",
                "это зона внимания; такой вывод остаётся под вопросом."
        ), "FACTOR_EFFECT_MISSING");

        WeeklyReviewAiValidationResult positive = validator.validate(
                input(false, "POSITIVE"),
                validResponse().replace(
                        "это зона внимания.",
                        "это положительный сигнал, но улучшения нет."
                )
        );
        assertThat(positive.violations())
                .extracting(value -> value.code())
                .contains("FACTOR_EFFECT_MISSING");
    }

    @Test
    void rejectsUnsupportedPreviousFullWeekQualifier() {
        assertViolation(validResponse().replace(
                "в периоде сравнения",
                "на предыдущей полной неделе"
        ), "UNAPPROVED_PERIOD_QUALIFIER");
    }

    @Test
    void rejectsChangedBackendOwnedActionCheck() {
        assertViolation(validResponse().replace(
                "Сравнить со следующей полной неделей",
                "Проверить результат через две недели"
        ), "ACTION_CHECK_MISMATCH");
    }

    private void assertViolation(String response, String code) {
        WeeklyReviewAiValidationResult result = validator.validate(
                input(false), response
        );

        assertThat(result.outcome())
                .isEqualTo(LlmValidationOutcome.SEMANTIC_INVALID);
        assertThat(result.violations())
                .extracting(value -> value.code())
                .contains(code);
    }

    private WeeklyReviewAiInput input(boolean causalLanguageAllowed) {
        return input(causalLanguageAllowed, "NEGATIVE", "POSITIVE");
    }

    private WeeklyReviewAiInput input(
            boolean causalLanguageAllowed,
            String factorEffect
    ) {
        return input(causalLanguageAllowed, factorEffect, "POSITIVE");
    }

    private WeeklyReviewAiInput input(
            boolean causalLanguageAllowed,
            String factorEffect,
            String summaryEffect
    ) {
        return new WeeklyReviewAiInput(
                WeeklyReviewAiContract.INPUT_SCHEMA_VERSION,
                WeeklyReviewAiContract.PROMPT_VERSION,
                WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION,
                new WeeklyReviewAiInput.SummarySource(
                        "Выручка выросла, возвраты требуют внимания.",
                        summaryEffect,
                        List.of("MIXED".equals(summaryEffect)
                                ? "Картина недели неоднозначная: выручка выросла, возвраты требуют внимания."
                                : "Неделя сильнее предыдущей: выручка выросла, возвраты требуют внимания."),
                        List.of(
                                "STORE.NET_REVENUE",
                                "STORE.RETURN_REVENUE"
                        ),
                        List.of()
                ),
                List.of(new WeeklyReviewAiInput.FactorSource(
                        "factor:return_revenue",
                        "Возвраты выросли",
                        "Возвраты выросли относительно предыдущей недели.",
                        "Возвраты уменьшили результат продаж сильнее, чем в периоде сравнения.",
                        factorEffect,
                        causalLanguageAllowed,
                        List.of("STORE.RETURN_REVENUE"),
                        List.of()
                )),
                List.of(new WeeklyReviewAiInput.ActionSource(
                        "action:restore:return_revenue",
                        "Разобрать рост возвратов",
                        "Сравнить со следующей полной неделей",
                        List.of("STORE.RETURN_REVENUE"),
                        List.of()
                )),
                List.of(
                        evidence("STORE.NET_REVENUE", "Чистая выручка"),
                        evidence("STORE.RETURN_REVENUE", "Возвраты")
                )
        );
    }

    private WeeklyReviewAiInput.EvidenceSource evidence(
            String reference,
            String label
    ) {
        return new WeeklyReviewAiInput.EvidenceSource(
                reference, label, "RUB", "available", "available"
        );
    }

    private String validResponse() {
        return """
                {
                  "schemaVersion": 4,
                  "summary": {
                    "text": "Неделя сильнее предыдущей: выручка выросла, возвраты требуют внимания.",
                    "evidenceRefs": ["STORE.NET_REVENUE", "STORE.RETURN_REVENUE"]
                  },
                  "factorExplanations": [
                    {
                      "factorId": "factor:return_revenue",
                      "text": "Возвраты уменьшили результат продаж сильнее, чем в \
                периоде сравнения — это зона внимания.",
                      "evidenceRefs": ["STORE.RETURN_REVENUE"]
                    }
                  ],
                  "actionWordings": [
                    {
                      "actionId": "action:restore:return_revenue",
                      "title": "Разобрать рост возвратов",
                      "check": "Сравнить со следующей полной неделей"
                    }
                  ]
                }
                """;
    }
}
