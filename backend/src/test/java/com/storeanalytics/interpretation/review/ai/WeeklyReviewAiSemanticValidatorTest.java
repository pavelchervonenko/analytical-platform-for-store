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
    void acceptsSelectorsAndReturnsRenderedUserText() {
        WeeklyReviewAiValidationResult result = validator.validate(
                WeeklyReviewAiTestFixtures.positiveWithReturnRisk(),
                WeeklyReviewAiTestFixtures.returnRiskSelection()
        );

        assertThat(result.outcome())
                .as("violations=%s", result.violations())
                .isEqualTo(LlmValidationOutcome.VALID);
        assertThat(result.semanticValidated()).isTrue();
        assertThat(result.content().summary().text())
                .contains("Неделя завершилась лучше периода сравнения")
                .contains("давление возвратов")
                .doesNotContain("SUMMARY_", "FACTOR_");
        assertThat(result.content().factorExplanations().getFirst().text())
                .isEqualTo(
                        "Давление возвратов на результат усилилось "
                                + "относительно периода сравнения. "
                                + "Это отдельная зона контроля."
                );
        assertThat(result.content().actionWordings().getFirst().title())
                .isEqualTo("Разобрать рост возвратов");
    }

    @Test
    void rejectsLegacyFreeTextResponseAtStructuralBoundary() {
        WeeklyReviewAiValidationResult result = validator.validate(
                WeeklyReviewAiTestFixtures.minimalInput("POSITIVE"),
                """
                {
                  "schemaVersion": 4,
                  "summary": {"text": "Произвольный текст"},
                  "factorExplanations": [],
                  "actionWordings": []
                }
                """
        );

        assertThat(result.outcome())
                .isEqualTo(LlmValidationOutcome.STRUCTURAL_INVALID);
        assertThat(result.semanticValidated()).isFalse();
    }

    @Test
    void rejectsUnknownOrMissingFactorSet() {
        assertViolation(
                WeeklyReviewAiTestFixtures.returnRiskSelection().replace(
                        "factor:return_revenue",
                        "factor:invented"
                ),
                "FACTOR_SET_MISMATCH"
        );
        assertViolation(
                WeeklyReviewAiTestFixtures.returnRiskSelection().replace(
                        """
                            {
                              "factorId": "factor:return_revenue",
                              "selector": "FACTOR_CONTROL"
                            }
                        """,
                        ""
                ),
                "FACTOR_SET_MISMATCH"
        );
    }

    @Test
    void rejectsSummarySelectorOutsideInputAllowlist() {
        assertViolation(
                WeeklyReviewAiTestFixtures.returnRiskSelection().replace(
                        "SUMMARY_RISK",
                        "SUMMARY_STRENGTH"
                ),
                "SUMMARY_SELECTOR_NOT_ALLOWED"
        );
    }

    @Test
    void rejectsUnexpectedSecondaryFocus() {
        assertViolation(
                riskSelectionWithSecondary(),
                "SUMMARY_FOCUS_UNEXPECTED"
        );
    }

    @Test
    void rejectsFactorSelectorOutsidePerFactorAllowlist() {
        assertViolation(
                WeeklyReviewAiTestFixtures.returnRiskSelection().replace(
                        "FACTOR_CONTROL",
                        "FACTOR_STRENGTH"
                ),
                "FACTOR_SELECTOR_NOT_ALLOWED"
        );
    }

    @Test
    void outcomeSelectorCannotHideKnownRisk() {
        WeeklyReviewAiInput input =
                WeeklyReviewAiTestFixtures.positiveWithReturnRisk();
        String response = WeeklyReviewAiTestFixtures.returnRiskSelection()
                .replace("SUMMARY_RISK", "SUMMARY_OUTCOME");

        WeeklyReviewAiValidationResult result = validator.validate(
                input, response
        );

        assertThat(result.violations())
                .extracting(value -> value.code())
                .contains("SUMMARY_SELECTOR_NOT_ALLOWED");
    }

    @Test
    void partialInputAddsBoundedConfidenceSentence() {
        WeeklyReviewAiValidationResult result = validator.validate(
                WeeklyReviewAiTestFixtures.minimalInput(
                        "NEUTRAL", "PARTIAL"
                ),
                WeeklyReviewAiTestFixtures.outcomeSelection()
        );

        assertThat(result.outcome()).isEqualTo(LlmValidationOutcome.VALID);
        assertThat(result.content().summary().text())
                .endsWith("Вывод основан только на доступной части данных.");
    }

    @Test
    void balancedSelectionSynthesizesStrengthAndRisk() {
        WeeklyReviewAiInput input = balancedInput();
        String selection = """
                {
                  "selectionSchemaVersion": 1,
                  "summary": {
                    "selector": "SUMMARY_BALANCED",
                    "primaryFactorId": "factor:accessory_attach",
                    "secondaryFactorId": "factor:return_revenue"
                  },
                  "factorSelections": [
                    {
                      "factorId": "factor:accessory_attach",
                      "selector": "FACTOR_STRENGTH"
                    },
                    {
                      "factorId": "factor:return_revenue",
                      "selector": "FACTOR_RISK"
                    }
                  ]
                }
                """;

        WeeklyReviewAiValidationResult result = validator.validate(
                input, selection
        );

        assertThat(result.outcome())
                .as("violations=%s", result.violations())
                .isEqualTo(LlmValidationOutcome.VALID);
        assertThat(result.content().summary().text())
                .contains("Сильная сторона")
                .contains("зона внимания")
                .contains("доля аксессуаров выросла")
                .contains("давление возвратов");
        assertThat(result.content().summary().evidenceRefs())
                .containsExactly(
                        "STORE.NET_REVENUE",
                        "STORE.ACCESSORY_ATTACH_RATE",
                        "STORE.RETURN_REVENUE"
                );
    }

    private String riskSelectionWithSecondary() {
        return """
                {
                  "selectionSchemaVersion": 1,
                  "summary": {
                    "selector": "SUMMARY_RISK",
                    "primaryFactorId": "factor:return_revenue",
                    "secondaryFactorId": "factor:another"
                  },
                  "factorSelections": [
                    {
                      "factorId": "factor:return_revenue",
                      "selector": "FACTOR_CONTROL"
                    }
                  ]
                }
                """;
    }

    private void assertViolation(String response, String code) {
        WeeklyReviewAiValidationResult result = validator.validate(
                WeeklyReviewAiTestFixtures.positiveWithReturnRisk(),
                response
        );

        assertThat(result.outcome())
                .isEqualTo(LlmValidationOutcome.SEMANTIC_INVALID);
        assertThat(result.violations())
                .extracting(value -> value.code())
                .contains(code);
    }

    private WeeklyReviewAiInput balancedInput() {
        return new WeeklyReviewAiInput(
                WeeklyReviewAiContract.INPUT_SCHEMA_VERSION,
                WeeklyReviewAiContract.PROMPT_VERSION,
                WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION,
                "READY",
                new WeeklyReviewAiInput.SummarySource(
                        "MIXED",
                        List.of("SUMMARY_BALANCED"),
                        List.of(
                                "factor:accessory_attach",
                                "factor:return_revenue"
                        ),
                        List.of("STORE.NET_REVENUE")
                ),
                List.of(
                        new WeeklyReviewAiInput.FactorSource(
                                "factor:accessory_attach",
                                "ATTACH_CHANGE",
                                "Доля аксессуаров выросла",
                                "UP",
                                "POSITIVE",
                                false,
                                List.of(
                                        "FACTOR_SIGNAL",
                                        "FACTOR_STRENGTH"
                                ),
                                List.of("STORE.ACCESSORY_ATTACH_RATE")
                        ),
                        new WeeklyReviewAiInput.FactorSource(
                                "factor:return_revenue",
                                "RETURN_CHANGE",
                                "Возвраты выросли",
                                "UP",
                                "NEGATIVE",
                                true,
                                List.of("FACTOR_RISK", "FACTOR_CONTROL"),
                                List.of("STORE.RETURN_REVENUE")
                        )
                ),
                List.of(),
                List.of(
                        WeeklyReviewAiTestFixtures.evidence(
                                "STORE.NET_REVENUE",
                                "Чистая выручка",
                                "RUB",
                                "126000",
                                "118000"
                        ),
                        WeeklyReviewAiTestFixtures.evidence(
                                "STORE.ACCESSORY_ATTACH_RATE",
                                "Доля аксессуаров",
                                "PERCENT",
                                "9.1",
                                "7.4"
                        ),
                        WeeklyReviewAiTestFixtures.evidence(
                                "STORE.RETURN_REVENUE",
                                "Возвраты",
                                "RUB",
                                "14000",
                                "7000"
                        )
                )
        );
    }
}
