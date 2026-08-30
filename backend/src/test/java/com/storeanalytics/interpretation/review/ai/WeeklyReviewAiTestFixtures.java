package com.storeanalytics.interpretation.review.ai;

import java.util.List;

final class WeeklyReviewAiTestFixtures {

    private WeeklyReviewAiTestFixtures() {
    }

    static WeeklyReviewAiInput minimalInput(String effect) {
        return minimalInput(effect, "READY");
    }

    static WeeklyReviewAiInput minimalInput(
            String effect,
            String reportState
    ) {
        return new WeeklyReviewAiInput(
                WeeklyReviewAiContract.INPUT_SCHEMA_VERSION,
                WeeklyReviewAiContract.PROMPT_VERSION,
                WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION,
                reportState,
                new WeeklyReviewAiInput.SummarySource(
                        effect,
                        List.of("SUMMARY_OUTCOME"),
                        List.of(),
                        List.of("STORE.NET_REVENUE")
                ),
                List.of(),
                List.of(),
                List.of(evidence(
                        "STORE.NET_REVENUE",
                        "Чистая выручка",
                        "RUB",
                        "1000",
                        "900"
                ))
        );
    }

    static WeeklyReviewAiInput positiveWithReturnRisk() {
        return new WeeklyReviewAiInput(
                WeeklyReviewAiContract.INPUT_SCHEMA_VERSION,
                WeeklyReviewAiContract.PROMPT_VERSION,
                WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION,
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
                        "Возвраты выросли",
                        "UP",
                        "NEGATIVE",
                        true,
                        List.of("FACTOR_RISK", "FACTOR_CONTROL"),
                        List.of("STORE.RETURN_REVENUE")
                )),
                List.of(new WeeklyReviewAiInput.ActionSource(
                        "action:restore:return_revenue",
                        "Разобрать рост возвратов",
                        "Сравнить со следующей полной неделей",
                        List.of("STORE.RETURN_REVENUE")
                )),
                List.of(
                        evidence(
                                "STORE.NET_REVENUE",
                                "Чистая выручка",
                                "RUB",
                                "120000",
                                "113315"
                        ),
                        evidence(
                                "STORE.RETURN_REVENUE",
                                "Возвраты",
                                "RUB",
                                "15000",
                                "8000"
                        )
                )
        );
    }

    static String outcomeSelection() {
        return """
                {
                  "selectionSchemaVersion": 1,
                  "summary": {
                    "selector": "SUMMARY_OUTCOME",
                    "primaryFactorId": null,
                    "secondaryFactorId": null
                  },
                  "factorSelections": []
                }
                """;
    }

    static String returnRiskSelection() {
        return """
                {
                  "selectionSchemaVersion": 1,
                  "summary": {
                    "selector": "SUMMARY_RISK",
                    "primaryFactorId": "factor:return_revenue",
                    "secondaryFactorId": null
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

    static WeeklyReviewAiInput.EvidenceSource evidence(
            String reference,
            String label,
            String unit,
            String current,
            String previous
    ) {
        return new WeeklyReviewAiInput.EvidenceSource(
                reference, label, unit, current, previous
        );
    }
}
