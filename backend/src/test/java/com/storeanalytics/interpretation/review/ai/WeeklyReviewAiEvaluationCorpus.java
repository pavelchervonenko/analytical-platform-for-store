package com.storeanalytics.interpretation.review.ai;

import com.storeanalytics.interpretation.validation.LlmValidationOutcome;
import java.util.ArrayList;
import java.util.List;

/** Versioned, network-free semantic corpus for the v24/schema4 boundary. */
final class WeeklyReviewAiEvaluationCorpus {

    static final String VERSION = "weekly-review-ai-eval-v5";

    private WeeklyReviewAiEvaluationCorpus() {
    }

    static List<EvaluationCase> cases() {
        WeeklyReviewAiInput standard = standardInput(false);
        String valid = validResponse();
        return appendNegationCases(List.of(
                valid("positive-growth", positiveInput(), positiveResponse()),
                valid("negative-returns", negativeInput(), negativeResponse()),
                valid("mixed-revenue-profit", mixedInput(), mixedResponse()),
                valid("neutral-no-material-change", minimalInput(), minimalResponse()),
                invalid("factor-management-meaning-missing", positiveInput(),
                        positiveResponse().replace(
                                "Аксессуары чаще дополняли базовые продажи, "
                                        + "чем в периоде сравнения — это "
                                        + "положительный сигнал.",
                                "Риск слабой доли аксессуаров снизился; "
                                        + "это положительный сигнал."
                        ),
                        "MANAGEMENT_MEANING_MISSING"),
                invalid("changed-action-operation", negativeInput(),
                        negativeResponse().replace(
                                "Разобрать рост возвратов",
                                "Вернуться к разбору возвратов"
                        ),
                        "ACTION_TITLE_CHANGED"),
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
                        valid.replace(
                                "Возвраты уменьшили результат продаж сильнее, чем в периоде "
                                        + "сравнения — это зона внимания.",
                                "Возвраты выросли на 12% относительно "
                                        + "предыдущей недели."),
                        "UNAPPROVED_NUMBER"),
                invalid("unapproved-causality", standard,
                        valid.replace(
                                "Возвраты уменьшили результат продаж сильнее, чем в периоде "
                                        + "сравнения — это зона внимания.",
                                "Выручка снизилась из-за роста возвратов."),
                        "UNAPPROVED_CAUSALITY"),
                invalid("unapproved-possible-effect", standard,
                        valid.replace(
                                "Возвраты уменьшили результат продаж сильнее, чем в периоде "
                                        + "сравнения — это зона внимания.",
                                "Возвраты могут способствовать снижению выручки."
                        ),
                        "UNAPPROVED_CAUSALITY"),
                invalid("cross-metric-factor", standard,
                        valid.replace(
                                "Возвраты уменьшили результат продаж сильнее, чем в периоде "
                                        + "сравнения — это зона внимания.",
                                "Возвраты выросли; общая выручка не оценивалась."
                        ),
                        "UNAPPROVED_METRIC"),
                invalid("unsupported-trend", standard,
                        valid.replace("Неделя сильнее предыдущей",
                                "Неделя сохранила положительный тренд"),
                        "UNSUPPORTED_TREND"),
                invalid("unapproved-summary-advice", standard,
                        valid.replace("Неделя сильнее предыдущей",
                                "Неделя сильнее; важно сохранить рост"),
                        "UNAPPROVED_ADVICE"),
                invalid("future-week-factor", standard,
                        valid.replace(
                                "это зона внимания.",
                                "это зона внимания на следующей неделе."
                        ),
                        "FUTURE_WEEK_REFERENCE"),
                invalid("unsupported-previous-full-week", standard,
                        valid.replace(
                                "в периоде сравнения",
                                "на предыдущей полной неделе"
                        ),
                        "UNAPPROVED_PERIOD_QUALIFIER"),
                invalid("monthly-plan-leak", standard,
                        valid.replace("Разобрать рост возвратов",
                                "Выполнить план месяца"),
                        "FORBIDDEN_HORIZON"),
                invalid("generic-employee-narrative", standard,
                        valid.replace("Разобрать рост возвратов",
                                "Сотрудник провёл ряд продаж"),
                        "GENERIC_NARRATIVE"),
                invalid("internal-identifier", standard,
                        valid.replace(
                                "Разобрать рост возвратов",
                                "Проверить 7571c8c5-a4f1-4a62-8f0c-"
                                        + "743a2d2b967d"
                        ),
                        "FORBIDDEN_IDENTIFIER"),
                invalid("summary-effect-contradiction", standard,
                        valid.replace("Неделя сильнее предыдущей",
                                "Неделя слабее предыдущей"),
                        "SUMMARY_NARRATIVE_CHANGED"),
                invalid("source-narrative-restated", standard,
                        valid.replace(
                                "Возвраты уменьшили результат продаж сильнее, чем в периоде "
                                        + "сравнения — это зона внимания.",
                                "Возвраты выросли относительно предыдущей недели."
                        ),
                        "SOURCE_NARRATIVE_RESTATED"),
                invalid("factor-effect-contradiction", standard,
                        valid.replace(
                                "Возвраты уменьшили результат продаж сильнее, чем в периоде "
                                        + "сравнения — это зона внимания.",
                                "Возвраты уменьшили результат продаж сильнее, чем в периоде "
                                        + "сравнения; это положительный сигнал."
                        ),
                        "FACTOR_EFFECT_CONTRADICTION"),
                invalid("short-action", standard,
                        valid.replace("Разобрать рост возвратов",
                                "Проверить"),
                        "ACTION_TITLE_WORD_COUNT"),
                invalid("desired-outcome-action", standard,
                        valid.replace("Разобрать рост возвратов",
                                "Восстановить уровень возвратов"),
                        "DESIRED_OUTCOME_ACTION"),
                invalid("duplicate-wording", standard,
                        valid.replace(
                                "Разобрать рост возвратов",
                                "Возвраты уменьшили результат продаж сильнее, чем в периоде "
                                        + "сравнения — это зона внимания."
                        ),
                        "DUPLICATE_NARRATIVE")
        ), standard, valid);
    }

    private static List<EvaluationCase> appendNegationCases(
            List<EvaluationCase> baseline,
            WeeklyReviewAiInput input,
            String valid
    ) {
        List<EvaluationCase> result = new ArrayList<>(baseline);
        result.add(invalid("negated-summary-effect", input,
                valid.replace("Неделя сильнее предыдущей",
                        "Неделя не сильнее предыдущей"),
                "SUMMARY_NARRATIVE_CHANGED"));
        result.add(invalid("negated-factor-effect", input,
                valid.replace("это зона внимания.",
                        "это не зона внимания."),
                "FACTOR_EFFECT_MISSING"));
        result.add(invalid("negated-management-meaning", input,
                valid.replace(
                        "Возвраты уменьшили результат продаж",
                        "Неверно, что Возвраты уменьшили результат продаж"
                ),
                "MANAGEMENT_MEANING_MISSING"));
        result.add(invalid("postposed-summary-negation", input,
                valid.replace("Неделя сильнее предыдущей",
                        "Неделя сильнее предыдущей не стала"),
                "SUMMARY_NARRATIVE_CHANGED"));
        result.add(invalid("postposed-factor-negation", input,
                valid.replace("это зона внимания.",
                        "зоной внимания это не является."),
                "FACTOR_EFFECT_MISSING"));
        result.add(invalid("modal-management-negation", input,
                valid.replace(
                        "Возвраты уменьшили результат продаж",
                        "Нельзя с достаточной уверенностью утверждать, что "
                                + "Возвраты уменьшили результат продаж"
                ),
                "MANAGEMENT_MEANING_MISSING"));
        result.add(invalid("denied-improvement", input,
                valid.replace("это зона внимания.",
                        "это зона внимания, но улучшения нет."),
                "FACTOR_EFFECT_MISSING"));
        result.add(invalid("modal-summary-assertion", input,
                valid.replace("Неделя сильнее предыдущей",
                        "Неделя может считаться сильнее предыдущей"),
                "SUMMARY_NARRATIVE_CHANGED"));
        result.add(invalid("disputed-summary-assertion", input,
                valid.replace(
                        "Неделя сильнее предыдущей: Чистая выручка выросла, "
                                + "возвраты требуют внимания.",
                        "Неделя сильнее предыдущей: Чистая выручка выросла, "
                                + "хотя это спорно."
                ),
                "SUMMARY_NARRATIVE_CHANGED"));
        result.add(invalid("modal-factor-assertion", input,
                valid.replace("это зона внимания.",
                        "это может считаться зоной внимания."),
                "FACTOR_EFFECT_MISSING"));
        result.add(invalid("questioned-factor-assertion", input,
                valid.replace("это зона внимания.",
                        "это зона внимания; такой вывод остаётся под вопросом."),
                "FACTOR_EFFECT_MISSING"));
        return List.copyOf(result);
    }

    static List<OnlineCase> onlineCases() {
        return List.of(
                new OnlineCase("positive-growth", positiveInput()),
                new OnlineCase("negative-returns", negativeInput()),
                new OnlineCase("mixed-revenue-profit", mixedInput()),
                new OnlineCase("neutral-no-material-change", minimalInput())
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

    private static WeeklyReviewAiInput positiveInput() {
        return new WeeklyReviewAiInput(
                WeeklyReviewAiContract.INPUT_SCHEMA_VERSION,
                WeeklyReviewAiContract.PROMPT_VERSION,
                WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION,
                new WeeklyReviewAiInput.SummarySource(
                        "Чистая выручка выросла на 5,9%, доля аксессуаров увеличилась.",
                        "POSITIVE",
                        List.of(
                                "Неделя сильнее предыдущей: Чистая выручка "
                                        + "выросла на 5,9%, доля аксессуаров увеличилась.",
                                "Неделя оказалась сильнее периода сравнения: "
                                        + "Чистая выручка выросла на 5,9%, "
                                        + "доля аксессуаров увеличилась."
                        ),
                        List.of("STORE.NET_REVENUE", "STORE.ACCESSORY_ATTACH_RATE"),
                        List.of("5,9", "120000", "113315", "8,4", "6,8")
                ),
                List.of(new WeeklyReviewAiInput.FactorSource(
                        "factor:accessory_attach",
                        "Доля аксессуаров выросла",
                        "Доля аксессуаров: 8,4% против 6,8%.",
                        "Аксессуары чаще дополняли базовые продажи, чем в периоде сравнения.",
                        "POSITIVE",
                        false,
                        List.of("STORE.ACCESSORY_ATTACH_RATE"),
                        List.of("8,4", "6,8")
                )),
                List.of(new WeeklyReviewAiInput.ActionSource(
                        "action:check:accessory_attach",
                        "Проверить долю аксессуаров",
                        "Сопоставить со следующей полной неделей",
                        List.of("STORE.ACCESSORY_ATTACH_RATE"),
                        List.of("8,4", "6,8")
                )),
                List.of(
                        evidence("STORE.NET_REVENUE", "Чистая выручка",
                                "RUB", "120000", "113315"),
                        evidence("STORE.ACCESSORY_ATTACH_RATE", "Доля аксессуаров",
                                "PERCENT", "8.4", "6.8")
                )
        );
    }

    private static WeeklyReviewAiInput negativeInput() {
        return new WeeklyReviewAiInput(
                WeeklyReviewAiContract.INPUT_SCHEMA_VERSION,
                WeeklyReviewAiContract.PROMPT_VERSION,
                WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION,
                new WeeklyReviewAiInput.SummarySource(
                        "Чистая выручка снизилась, возвраты выросли.",
                        "NEGATIVE",
                        List.of(
                                "Неделя слабее предыдущей: Чистая выручка снизилась, возвраты выросли.",
                                "Неделя оказалась слабее периода сравнения: Чистая выручка снизилась, возвраты выросли."
                        ),
                        List.of("STORE.NET_REVENUE", "STORE.RETURN_REVENUE"),
                        List.of("98000", "108000", "15000", "8000")
                ),
                List.of(new WeeklyReviewAiInput.FactorSource(
                        "factor:return_revenue",
                        "Возвраты выросли",
                        "Возвраты выросли относительно предыдущей недели.",
                        "Возвраты уменьшили результат продаж сильнее, чем в периоде сравнения.",
                        "NEGATIVE",
                        false,
                        List.of("STORE.RETURN_REVENUE"),
                        List.of("15000", "8000")
                )),
                List.of(new WeeklyReviewAiInput.ActionSource(
                        "action:restore:return_revenue",
                        "Разобрать рост возвратов",
                        "Сравнить со следующей полной неделей",
                        List.of("STORE.RETURN_REVENUE"),
                        List.of("15000", "8000")
                )),
                List.of(
                        evidence("STORE.NET_REVENUE", "Чистая выручка",
                                "RUB", "98000", "108000"),
                        evidence("STORE.RETURN_REVENUE", "Возвраты",
                                "RUB", "15000", "8000")
                )
        );
    }

    private static WeeklyReviewAiInput mixedInput() {
        return new WeeklyReviewAiInput(
                WeeklyReviewAiContract.INPUT_SCHEMA_VERSION,
                WeeklyReviewAiContract.PROMPT_VERSION,
                WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION,
                new WeeklyReviewAiInput.SummarySource(
                        "Чистая выручка выросла, валовая прибыль снизилась.",
                        "MIXED",
                        List.of(
                                "Картина недели неоднозначная: Чистая выручка выросла, валовая прибыль снизилась.",
                                "Сигналы недели разнонаправлены: Чистая выручка выросла, валовая прибыль снизилась."
                        ),
                        List.of("STORE.NET_REVENUE", "STORE.GROSS_PROFIT"),
                        List.of("126000", "118000", "31000", "36000")
                ),
                List.of(
                        new WeeklyReviewAiInput.FactorSource(
                                "factor:accessory_attach",
                                "Доля аксессуаров выросла",
                                "Доля аксессуаров выросла относительно предыдущей недели.",
                                "Аксессуары чаще дополняли базовые продажи, чем в периоде сравнения.",
                                "POSITIVE",
                                false,
                                List.of("STORE.ACCESSORY_ATTACH_RATE"),
                                List.of("9,1", "7,4")
                        ),
                        new WeeklyReviewAiInput.FactorSource(
                                "factor:return_revenue",
                                "Возвраты выросли",
                                "Возвраты выросли относительно предыдущей недели.",
                                "Возвраты уменьшили результат продаж сильнее, чем в периоде сравнения.",
                                "NEGATIVE",
                                false,
                                List.of("STORE.RETURN_REVENUE"),
                                List.of("14000", "7000")
                        )
                ),
                List.of(
                        new WeeklyReviewAiInput.ActionSource(
                                "action:check:accessory_attach",
                                "Проверить долю аксессуаров",
                                "Сопоставить со следующей полной неделей",
                                List.of("STORE.ACCESSORY_ATTACH_RATE"),
                                List.of("9,1", "7,4")
                        ),
                        new WeeklyReviewAiInput.ActionSource(
                                "action:restore:return_revenue",
                                "Разобрать рост возвратов",
                                "Сравнить со следующей полной неделей",
                                List.of("STORE.RETURN_REVENUE"),
                                List.of("14000", "7000")
                        )
                ),
                List.of(
                        evidence("STORE.NET_REVENUE", "Чистая выручка",
                                "RUB", "126000", "118000"),
                        evidence("STORE.GROSS_PROFIT", "Валовая прибыль",
                                "RUB", "31000", "36000"),
                        evidence("STORE.ACCESSORY_ATTACH_RATE", "Доля аксессуаров",
                                "PERCENT", "9.1", "7.4"),
                        evidence("STORE.RETURN_REVENUE", "Возвраты",
                                "RUB", "14000", "7000")
                )
        );
    }

    private static WeeklyReviewAiInput standardInput(boolean causalAllowed) {
        return new WeeklyReviewAiInput(
                WeeklyReviewAiContract.INPUT_SCHEMA_VERSION,
                WeeklyReviewAiContract.PROMPT_VERSION,
                WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION,
                new WeeklyReviewAiInput.SummarySource(
                        "Чистая выручка выросла, возвраты требуют внимания.",
                        "POSITIVE",
                        List.of(
                                "Неделя сильнее предыдущей: Чистая выручка выросла, возвраты требуют внимания.",
                                "Неделя оказалась сильнее периода сравнения: "
                                        + "Чистая выручка выросла, возвраты "
                                        + "требуют внимания."
                        ),
                        List.of("STORE.NET_REVENUE", "STORE.RETURN_REVENUE"),
                        List.of("120000", "113315", "15000", "8000")
                ),
                List.of(new WeeklyReviewAiInput.FactorSource(
                        "factor:return_revenue",
                        "Возвраты выросли",
                        "Возвраты выросли относительно предыдущей недели.",
                        "Возвраты уменьшили результат продаж сильнее, чем в периоде сравнения.",
                        "NEGATIVE",
                        causalAllowed,
                        List.of("STORE.RETURN_REVENUE"),
                        List.of("15000", "8000")
                )),
                List.of(new WeeklyReviewAiInput.ActionSource(
                        "action:restore:return_revenue",
                        "Разобрать рост возвратов",
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
                WeeklyReviewAiContract.INPUT_SCHEMA_VERSION,
                WeeklyReviewAiContract.PROMPT_VERSION,
                WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION,
                new WeeklyReviewAiInput.SummarySource(
                        "Существенных изменений относительно предыдущей недели нет.",
                        "NEUTRAL",
                        List.of(
                                "Стабильная картина недели: Существенных изменений относительно предыдущей недели нет.",
                                "Ключевые результаты без существенных "
                                        + "изменений: Существенных изменений "
                                        + "относительно предыдущей недели нет."
                        ),
                        List.of("STORE.NET_REVENUE"),
                        List.of("0")
                ),
                List.of(),
                List.of(),
                List.of(evidence("STORE.NET_REVENUE", "Чистая выручка",
                        "0", "0"))
        );
    }

    private static WeeklyReviewAiInput.EvidenceSource evidence(
            String reference,
            String label,
            String current,
            String previous
    ) {
        return evidence(reference, label, "RUB", current, previous);
    }

    private static WeeklyReviewAiInput.EvidenceSource evidence(
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

    private static String positiveResponse() {
        return """
                {
                  "schemaVersion": 4,
                  "summary": {
                    "text": "Неделя сильнее предыдущей: Чистая выручка выросла на 5,9%, доля аксессуаров увеличилась.",
                    "evidenceRefs": ["STORE.NET_REVENUE", "STORE.ACCESSORY_ATTACH_RATE"]
                  },
                  "factorExplanations": [
                    {
                      "factorId": "factor:accessory_attach",
                      "text": "Аксессуары чаще дополняли базовые продажи, чем в \
                периоде сравнения — это положительный сигнал.",
                      "evidenceRefs": ["STORE.ACCESSORY_ATTACH_RATE"]
                    }
                  ],
                  "actionWordings": [
                    {
                      "actionId": "action:check:accessory_attach",
                      "title": "Проверить долю аксессуаров",
                      "check": "Сопоставить со следующей полной неделей"
                    }
                  ]
                }
                """;
    }

    private static String negativeResponse() {
        return """
                {
                  "schemaVersion": 4,
                  "summary": {
                    "text": "Неделя слабее предыдущей: Чистая выручка снизилась, возвраты выросли.",
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

    private static String mixedResponse() {
        return """
                {
                  "schemaVersion": 4,
                  "summary": {
                    "text": "Картина недели неоднозначная: Чистая выручка выросла, валовая прибыль снизилась.",
                    "evidenceRefs": ["STORE.NET_REVENUE", "STORE.GROSS_PROFIT"]
                  },
                  "factorExplanations": [
                    {
                      "factorId": "factor:accessory_attach",
                      "text": "Аксессуары чаще дополняли базовые продажи, чем в \
                периоде сравнения — это положительный сигнал.",
                      "evidenceRefs": ["STORE.ACCESSORY_ATTACH_RATE"]
                    },
                    {
                      "factorId": "factor:return_revenue",
                      "text": "Возвраты уменьшили результат продаж сильнее, чем в \
                периоде сравнения — это зона внимания.",
                      "evidenceRefs": ["STORE.RETURN_REVENUE"]
                    }
                  ],
                  "actionWordings": [
                    {
                      "actionId": "action:check:accessory_attach",
                      "title": "Проверить долю аксессуаров",
                      "check": "Сопоставить со следующей полной неделей"
                    },
                    {
                      "actionId": "action:restore:return_revenue",
                      "title": "Разобрать рост возвратов",
                      "check": "Сравнить со следующей полной неделей"
                    }
                  ]
                }
                """;
    }

    private static String validResponse() {
        return """
                {
                  "schemaVersion": 4,
                  "summary": {
                    "text": "Неделя сильнее предыдущей: Чистая выручка выросла, возвраты требуют внимания.",
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

    private static String minimalResponse() {
        return """
                {
                  "schemaVersion": 4,
                  "summary": {
                    "text": "Стабильная картина недели: Существенных изменений относительно предыдущей недели нет.",
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
