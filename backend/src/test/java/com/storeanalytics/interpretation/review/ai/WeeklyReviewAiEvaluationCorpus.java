package com.storeanalytics.interpretation.review.ai;

import com.storeanalytics.interpretation.validation.LlmValidationOutcome;
import java.util.List;

/** Versioned, network-free semantic corpus for the v22/schema4 boundary. */
final class WeeklyReviewAiEvaluationCorpus {

    static final String VERSION = "weekly-review-ai-eval-v3";

    private WeeklyReviewAiEvaluationCorpus() {
    }

    static List<EvaluationCase> cases() {
        WeeklyReviewAiInput standard = standardInput(false);
        String valid = validResponse();
        return List.of(
                valid("ready-growth", standard, valid),
                valid("partial-return", standardInput(true), causalResponse()),
                valid("no-material-change", minimalInput(), minimalResponse()),
                valid("exact-numeric-literals", numericInput(), numericResponse()),
                invalid("malformed-json", standard, "{", "INVALID_JSON"),
                invalid("unknown-root-field", standard,
                        valid.replace("\"schemaVersion\": 4,",
                                "\"schemaVersion\": 4, \"extra\": true,"),
                        "SCHEMA_ADDITIONALPROPERTIES"),
                invalid("wrong-schema-version", standard,
                        valid.replace("\"schemaVersion\": 4",
                                "\"schemaVersion\": 3"),
                        "SCHEMA_CONST"),
                invalid("factor-set-mismatch", standard,
                        valid.replace("\"factor:return_revenue\"",
                                "\"factor:invented\""),
                        "FACTOR_SET_MISMATCH"),
                invalid("action-set-mismatch", standard,
                        valid.replace("\"action:restore:return_revenue\"",
                                "\"action:invented\""),
                        "ACTION_SET_MISMATCH"),
                invalid("evidence-mismatch", standard,
                        valid.replace(
                                "\"evidenceRefs\": [\"STORE.RETURN_REVENUE\"]",
                                "\"evidenceRefs\": [\"STORE.NET_REVENUE\"]"),
                        "FACTOR_EVIDENCE_MISMATCH"),
                invalid("changed-action-check", standard,
                        valid.replace("Сравнить со следующей полной неделей",
                                "Проверить через две недели"),
                        "ACTION_CHECK_MISMATCH"),
                invalid("invented-number", standard,
                        valid.replace("Возвраты выросли относительно предыдущей недели.",
                                "Возвраты выросли на 12% относительно предыдущей недели."),
                        "UNAPPROVED_NUMBER"),
                invalid("unapproved-causality", standard,
                        valid.replace("Возвраты выросли относительно предыдущей недели.",
                                "Выручка снизилась из-за роста возвратов."),
                        "UNAPPROVED_CAUSALITY"),
                invalid("monthly-plan-leak", standard,
                        valid.replace("Проверить возвраты",
                                "Выполнить план месяца"),
                        "FORBIDDEN_HORIZON"),
                invalid("generic-employee-narrative", standard,
                        valid.replace("Проверить возвраты",
                                "Сотрудник провёл ряд продаж"),
                        "GENERIC_NARRATIVE"),
                invalid("internal-identifier", standard,
                        valid.replace("Проверить возвраты",
                                "Проверить 7571c8c5-a4f1-4a62-8f0c-743a2d2b967d"),
                        "FORBIDDEN_IDENTIFIER"),
                invalid("duplicate-wording", standard,
                        valid.replace("Проверить возвраты",
                                "Возвраты выросли относительно предыдущей недели."),
                        "DUPLICATE_NARRATIVE")
        );
    }

    static List<OnlineCase> onlineCases() {
        return List.of(
                new OnlineCase("ready-growth", standardInput(false)),
                new OnlineCase("partial-return", standardInput(true)),
                new OnlineCase("no-material-change", minimalInput()),
                new OnlineCase("exact-numeric-literals", numericInput())
        );
    }

    private static EvaluationCase valid(
            String id,
            WeeklyReviewAiInput input,
            String responseBody
    ) {
        return new EvaluationCase(
                id, input, responseBody, LlmValidationOutcome.VALID, null
        );
    }

    private static EvaluationCase invalid(
            String id,
            WeeklyReviewAiInput input,
            String responseBody,
            String violationCode
    ) {
        LlmValidationOutcome outcome = violationCode.startsWith("SCHEMA_")
                || "INVALID_JSON".equals(violationCode)
                ? LlmValidationOutcome.STRUCTURAL_INVALID
                : LlmValidationOutcome.SEMANTIC_INVALID;
        return new EvaluationCase(id, input, responseBody, outcome, violationCode);
    }

    private static WeeklyReviewAiInput standardInput(boolean causalAllowed) {
        return new WeeklyReviewAiInput(
                1,
                WeeklyReviewAiContract.PROMPT_VERSION,
                WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION,
                new WeeklyReviewAiInput.SummarySource(
                        "Чистая выручка выросла, возвраты требуют внимания.",
                        List.of("STORE.NET_REVENUE", "STORE.RETURN_REVENUE"),
                        List.of("120000", "113315", "15000", "8000")
                ),
                List.of(new WeeklyReviewAiInput.FactorSource(
                        "factor:return_revenue",
                        "Возвраты выросли",
                        "Возвраты выросли относительно предыдущей недели.",
                        "NEGATIVE",
                        causalAllowed,
                        List.of("STORE.RETURN_REVENUE"),
                        List.of("15000", "8000")
                )),
                List.of(new WeeklyReviewAiInput.ActionSource(
                        "action:restore:return_revenue",
                        "Проверить возвраты",
                        "Сравнить со следующей полной неделей",
                        List.of("STORE.RETURN_REVENUE"),
                        List.of("15000", "8000")
                )),
                List.of(
                        evidence("STORE.NET_REVENUE", "Чистая выручка",
                                "120000", "113315"),
                        evidence("STORE.RETURN_REVENUE", "Возвраты",
                                "15000", "8000")
                )
        );
    }

    private static WeeklyReviewAiInput minimalInput() {
        return new WeeklyReviewAiInput(
                1,
                WeeklyReviewAiContract.PROMPT_VERSION,
                WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION,
                new WeeklyReviewAiInput.SummarySource(
                        "Существенных изменений относительно предыдущей недели нет.",
                        List.of("STORE.NET_REVENUE"),
                        List.of("0")
                ),
                List.of(),
                List.of(),
                List.of(evidence("STORE.NET_REVENUE", "Чистая выручка",
                        "0", "0"))
        );
    }

    private static WeeklyReviewAiInput numericInput() {
        return new WeeklyReviewAiInput(
                1,
                WeeklyReviewAiContract.PROMPT_VERSION,
                WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION,
                new WeeklyReviewAiInput.SummarySource(
                        "Чистая выручка выросла на 5,9%.",
                        List.of("STORE.NET_REVENUE"),
                        List.of("5,9", "120000", "113315")
                ),
                List.of(),
                List.of(),
                List.of(evidence("STORE.NET_REVENUE", "Чистая выручка",
                        "120000", "113315"))
        );
    }

    private static WeeklyReviewAiInput.EvidenceSource evidence(
            String reference,
            String label,
            String current,
            String previous
    ) {
        return new WeeklyReviewAiInput.EvidenceSource(
                reference, label, "RUB", current, previous
        );
    }

    private static String validResponse() {
        return """
                {
                  "schemaVersion": 4,
                  "summary": {
                    "text": "Чистая выручка выросла, возвраты требуют внимания.",
                    "evidenceRefs": ["STORE.NET_REVENUE", "STORE.RETURN_REVENUE"]
                  },
                  "factorExplanations": [
                    {
                      "factorId": "factor:return_revenue",
                      "text": "Возвраты выросли относительно предыдущей недели.",
                      "evidenceRefs": ["STORE.RETURN_REVENUE"]
                    }
                  ],
                  "actionWordings": [
                    {
                      "actionId": "action:restore:return_revenue",
                      "title": "Проверить возвраты",
                      "check": "Сравнить со следующей полной неделей"
                    }
                  ]
                }
                """;
    }

    private static String causalResponse() {
        return validResponse().replace(
                "Возвраты выросли относительно предыдущей недели.",
                "Чистая выручка изменилась из-за роста возвратов."
        );
    }

    private static String minimalResponse() {
        return """
                {
                  "schemaVersion": 4,
                  "summary": {
                    "text": "Существенных изменений относительно предыдущей недели нет.",
                    "evidenceRefs": ["STORE.NET_REVENUE"]
                  },
                  "factorExplanations": [],
                  "actionWordings": []
                }
                """;
    }

    private static String numericResponse() {
        return """
                {
                  "schemaVersion": 4,
                  "summary": {
                    "text": "Чистая выручка: 120 000 рублей; рост — 5,9 %.",
                    "evidenceRefs": ["STORE.NET_REVENUE"]
                  },
                  "factorExplanations": [],
                  "actionWordings": []
                }
                """;
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
