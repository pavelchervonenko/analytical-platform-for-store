package com.storeanalytics.interpretation.review.ai;

import com.storeanalytics.interpretation.validation.LlmValidationOutcome;
import java.util.ArrayList;
import java.util.List;

/** Versioned network-free corpus for the v25 selector and renderer boundary. */
final class WeeklyReviewAiEvaluationCorpus {

    static final String VERSION = "weekly-review-ai-eval-v6";

    private WeeklyReviewAiEvaluationCorpus() {
    }

    static List<EvaluationCase> cases() {
        List<EvaluationCase> result = new ArrayList<>();
        result.addAll(validCases());
        result.addAll(structuralCases());
        result.addAll(factorBoundaryCases());
        result.addAll(summaryBoundaryCases());
        result.addAll(balancedBoundaryCases());
        return List.copyOf(result);
    }

    private static List<EvaluationCase> validCases() {
        return List.of(
                valid(
                        "positive-outcome",
                        WeeklyReviewAiTestFixtures.minimalInput("POSITIVE"),
                        WeeklyReviewAiTestFixtures.outcomeSelection()
                ),
                valid(
                        "negative-outcome",
                        WeeklyReviewAiTestFixtures.minimalInput("NEGATIVE"),
                        WeeklyReviewAiTestFixtures.outcomeSelection()
                ),
                valid(
                        "neutral-outcome",
                        WeeklyReviewAiTestFixtures.minimalInput("NEUTRAL"),
                        WeeklyReviewAiTestFixtures.outcomeSelection()
                ),
                valid(
                        "partial-outcome",
                        WeeklyReviewAiTestFixtures.minimalInput(
                                "MIXED", "PARTIAL"
                        ),
                        WeeklyReviewAiTestFixtures.outcomeSelection()
                ),
                valid(
                        "positive-with-risk",
                        riskInput(),
                        riskSelection()
                ),
                valid(
                        "balanced-strength-risk",
                        balancedInput(),
                        balancedSelection()
                )
        );
    }

    private static List<EvaluationCase> structuralCases() {
        WeeklyReviewAiInput risk = riskInput();
        String riskSelection = riskSelection();
        return List.of(
                invalid(
                        "blank-response",
                        risk,
                        " ",
                        LlmValidationOutcome.STRUCTURAL_INVALID,
                        "INVALID_JSON"
                ),
                invalid(
                        "malformed-json",
                        risk,
                        "{",
                        LlmValidationOutcome.STRUCTURAL_INVALID,
                        "INVALID_JSON"
                ),
                invalid(
                        "legacy-free-text-content",
                        risk,
                        """
                        {
                          "schemaVersion": 4,
                          "summary": {"text": "Произвольный вывод"},
                          "factorExplanations": [],
                          "actionWordings": []
                        }
                        """,
                        LlmValidationOutcome.STRUCTURAL_INVALID,
                        "SCHEMA_ADDITIONALPROPERTIES"
                ),
                invalid(
                        "unknown-root-field",
                        risk,
                        """
                        {
                          "selectionSchemaVersion": 1,
                          "extra": true,
                          "summary": {
                            "selector": "SUMMARY_RISK",
                            "primaryFactorId": "factor:return_revenue",
                            "secondaryFactorId": null
                          },
                          "factorSelections": []
                        }
                        """,
                        LlmValidationOutcome.STRUCTURAL_INVALID,
                        "SCHEMA_ADDITIONALPROPERTIES"
                ),
                invalid(
                        "wrong-selection-schema-version",
                        risk,
                        """
                        {
                          "selectionSchemaVersion": 2,
                          "summary": {
                            "selector": "SUMMARY_OUTCOME",
                            "primaryFactorId": null,
                            "secondaryFactorId": null
                          },
                          "factorSelections": []
                        }
                        """,
                        LlmValidationOutcome.STRUCTURAL_INVALID,
                        "SCHEMA_CONST"
                ),
                invalid(
                        "missing-summary-field",
                        risk,
                        riskSelection.replace(
                                "secondaryFactorId",
                                "ignoredFactorId"
                        ),
                        LlmValidationOutcome.STRUCTURAL_INVALID,
                        "SCHEMA_REQUIRED"
                ),
                invalid(
                        "unknown-selector-token",
                        risk,
                        riskSelection.replace(
                                "FACTOR_CONTROL",
                                "FACTOR_INVENTED"
                        ),
                        LlmValidationOutcome.STRUCTURAL_INVALID,
                        "SCHEMA_ENUM"
                )
        );
    }

    private static List<EvaluationCase> factorBoundaryCases() {
        WeeklyReviewAiInput risk = riskInput();
        return List.of(
                invalid(
                        "missing-factor-selection",
                        risk,
                        selection(
                                "SUMMARY_RISK",
                                "factor:return_revenue",
                                null,
                                ""
                        ),
                        LlmValidationOutcome.SEMANTIC_INVALID,
                        "FACTOR_SET_MISMATCH"
                ),
                invalid(
                        "unknown-factor-id",
                        risk,
                        selection(
                                "SUMMARY_RISK",
                                "factor:return_revenue",
                                null,
                                factorSelection(
                                        "factor:invented",
                                        "FACTOR_CONTROL"
                                )
                        ),
                        LlmValidationOutcome.SEMANTIC_INVALID,
                        "FACTOR_SET_MISMATCH"
                ),
                invalid(
                        "factor-selector-not-allowed",
                        risk,
                        selection(
                                "SUMMARY_RISK",
                                "factor:return_revenue",
                                null,
                                factorSelection(
                                        "factor:return_revenue",
                                        "FACTOR_STRENGTH"
                                )
                        ),
                        LlmValidationOutcome.SEMANTIC_INVALID,
                        "FACTOR_SELECTOR_NOT_ALLOWED"
                )
        );
    }

    private static List<EvaluationCase> summaryBoundaryCases() {
        WeeklyReviewAiInput risk = riskInput();
        String riskFactor = riskFactor();
        return List.of(
                invalid(
                        "summary-selector-not-allowed",
                        risk,
                        selection(
                                "SUMMARY_STRENGTH",
                                "factor:return_revenue",
                                null,
                                riskFactor
                        ),
                        LlmValidationOutcome.SEMANTIC_INVALID,
                        "SUMMARY_SELECTOR_NOT_ALLOWED"
                ),
                invalid(
                        "outcome-selector-not-allowed",
                        risk,
                        selection(
                                "SUMMARY_OUTCOME",
                                "factor:return_revenue",
                                null,
                                riskFactor
                        ),
                        LlmValidationOutcome.SEMANTIC_INVALID,
                        "SUMMARY_SELECTOR_NOT_ALLOWED"
                ),
                invalid(
                        "risk-missing-focus",
                        risk,
                        selection(
                                "SUMMARY_RISK", null, null, riskFactor
                        ),
                        LlmValidationOutcome.SEMANTIC_INVALID,
                        "SUMMARY_FOCUS_EFFECT_MISMATCH"
                ),
                invalid(
                        "risk-has-duplicate-focus",
                        risk,
                        selection(
                                "SUMMARY_RISK",
                                "factor:return_revenue",
                                "factor:return_revenue",
                                riskFactor
                        ),
                        LlmValidationOutcome.STRUCTURAL_INVALID,
                        "TYPED_CONTRACT"
                ),
                invalid(
                        "focus-not-allowed",
                        risk,
                        selection(
                                "SUMMARY_RISK",
                                "factor:unknown",
                                null,
                                riskFactor
                        ),
                        LlmValidationOutcome.SEMANTIC_INVALID,
                        "SUMMARY_FOCUS_NOT_ALLOWED"
                )
        );
    }

    private static List<EvaluationCase> balancedBoundaryCases() {
        WeeklyReviewAiInput balanced = balancedInput();
        String positiveFactor = positiveFactor();
        String riskFactor = riskFactor();
        return List.of(
                invalid(
                        "balanced-reordered-factor-set",
                        balanced,
                        selection(
                                "SUMMARY_BALANCED",
                                "factor:accessory_attach",
                                "factor:return_revenue",
                                riskFactor + "," + positiveFactor
                        ),
                        LlmValidationOutcome.SEMANTIC_INVALID,
                        "FACTOR_SET_MISMATCH"
                ),
                invalid(
                        "balanced-focus-effects-reversed",
                        balanced,
                        selection(
                                "SUMMARY_BALANCED",
                                "factor:return_revenue",
                                "factor:accessory_attach",
                                positiveFactor + "," + riskFactor
                        ),
                        LlmValidationOutcome.SEMANTIC_INVALID,
                        "SUMMARY_FOCUS_EFFECT_MISMATCH"
                ),
                invalid(
                        "balanced-duplicate-focus",
                        balanced,
                        selection(
                                "SUMMARY_BALANCED",
                                "factor:accessory_attach",
                                "factor:accessory_attach",
                                positiveFactor + "," + riskFactor
                        ),
                        LlmValidationOutcome.STRUCTURAL_INVALID,
                        "TYPED_CONTRACT"
                ),
                invalid(
                        "positive-factor-uses-risk-framing",
                        balanced,
                        selection(
                                "SUMMARY_BALANCED",
                                "factor:accessory_attach",
                                "factor:return_revenue",
                                factorSelection(
                                        "factor:accessory_attach",
                                        "FACTOR_RISK"
                                ) + "," + riskFactor
                        ),
                        LlmValidationOutcome.SEMANTIC_INVALID,
                        "FACTOR_SELECTOR_NOT_ALLOWED"
                )
        );
    }

    static List<OnlineCase> onlineCases() {
        return List.of(
                new OnlineCase(
                        "positive-outcome",
                        WeeklyReviewAiTestFixtures.minimalInput("POSITIVE")
                ),
                new OnlineCase("positive-with-risk", riskInput()),
                new OnlineCase("balanced-strength-risk", balancedInput()),
                new OnlineCase(
                        "neutral-outcome",
                        WeeklyReviewAiTestFixtures.minimalInput("NEUTRAL")
                )
        );
    }

    private static EvaluationCase valid(
            String id,
            WeeklyReviewAiInput input,
            String responseBody
    ) {
        return new EvaluationCase(
                id,
                input,
                responseBody,
                LlmValidationOutcome.VALID,
                null
        );
    }

    private static EvaluationCase invalid(
            String id,
            WeeklyReviewAiInput input,
            String responseBody,
            LlmValidationOutcome outcome,
            String violationCode
    ) {
        return new EvaluationCase(
                id, input, responseBody, outcome, violationCode
        );
    }

    private static WeeklyReviewAiInput riskInput() {
        return WeeklyReviewAiTestFixtures.positiveWithReturnRisk();
    }

    private static WeeklyReviewAiInput balancedInput() {
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

    private static String riskSelection() {
        return selection(
                "SUMMARY_RISK",
                "factor:return_revenue",
                null,
                riskFactor()
        );
    }

    private static String balancedSelection() {
        return selection(
                "SUMMARY_BALANCED",
                "factor:accessory_attach",
                "factor:return_revenue",
                positiveFactor() + "," + riskFactor()
        );
    }

    private static String positiveFactor() {
        return factorSelection(
                "factor:accessory_attach", "FACTOR_STRENGTH"
        );
    }

    private static String riskFactor() {
        return factorSelection(
                "factor:return_revenue", "FACTOR_CONTROL"
        );
    }

    private static String selection(
            String selector,
            String primaryFactorId,
            String secondaryFactorId,
            String factors
    ) {
        return """
                {
                  "selectionSchemaVersion": 1,
                  "summary": {
                    "selector": "%s",
                    "primaryFactorId": %s,
                    "secondaryFactorId": %s
                  },
                  "factorSelections": [%s]
                }
                """.formatted(
                        selector,
                        jsonString(primaryFactorId),
                        jsonString(secondaryFactorId),
                        factors
                );
    }

    private static String factorSelection(
            String factorId,
            String selector
    ) {
        return """
                {
                  "factorId": "%s",
                  "selector": "%s"
                }
                """.formatted(factorId, selector);
    }

    private static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        String quote = Character.toString(34);
        return quote + value + quote;
    }

    record EvaluationCase(
            String id,
            WeeklyReviewAiInput input,
            String responseBody,
            LlmValidationOutcome expectedOutcome,
            String requiredViolationCode
    ) {
    }

    record OnlineCase(String id, WeeklyReviewAiInput input) {
    }
}
