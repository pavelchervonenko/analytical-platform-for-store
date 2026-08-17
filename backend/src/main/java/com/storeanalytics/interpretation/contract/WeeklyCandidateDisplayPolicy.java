package com.storeanalytics.interpretation.contract;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.CandidateKind;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.CandidateSignal;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;

/** Creates exact backend-owned user-facing copy for verified candidate signals. */
public final class WeeklyCandidateDisplayPolicy {

    private WeeklyCandidateDisplayPolicy() {
    }

    public static Narrative forCandidate(CandidateSignal candidate) {
        return switch (candidate.theme()) {
            case "PLAN" -> plan(candidate.kind());
            case "REVENUE_DYNAMICS" -> directional(
                    "Динамика выручки",
                    "Выручка существенно снизилась относительно прошлого периода.",
                    "Выручка существенно выросла относительно прошлого периода.",
                    candidate.kind()
            );
            case "PROFITABILITY" -> directional(
                    "Динамика валовой прибыли",
                    "Валовая прибыль существенно снизилась относительно прошлого периода.",
                    "Валовая прибыль существенно выросла относительно прошлого периода.",
                    candidate.kind()
            );
            case "CATEGORY_MIX" -> directional(
                    "Динамика категории",
                    "Выручка и доля выбранной категории существенно снизились.",
                    "Выручка и доля выбранной категории существенно выросли.",
                    candidate.kind()
            );
            case "ADDITIONAL_SALES" -> directional(
                    "Дополнительные продажи",
                    "Выручка дополнительных продаж существенно снизилась.",
                    "Выручка дополнительных продаж существенно выросла.",
                    candidate.kind()
            );
            case "ATTACH_RATE" -> directional(
                    "Прикрепление дополнительных позиций",
                    "Частота дополнительных продаж существенно снизилась при достаточной базе.",
                    "Частота дополнительных продаж существенно выросла при достаточной базе.",
                    candidate.kind()
            );
            case "EMPLOYEE_PERFORMANCE" -> directional(
                    "Динамика результата сотрудника",
                    "Результат сотрудника существенно снизился относительно его прошлого периода.",
                    "Результат сотрудника существенно улучшился относительно его прошлого периода.",
                    candidate.kind()
            );
            default -> fallback(candidate);
        };
    }

    public static Narrative forCandidate(
            CandidateSignal candidate,
            WeeklyInterpretationInput input
    ) {
        Narrative contextual = contextualNarrative(candidate, input);
        return contextual == null ? forCandidate(candidate) : contextual;
    }

    public static List<String> evidenceRefs(
            CandidateSignal candidate,
            WeeklyInterpretationInput input
    ) {
        LinkedHashSet<String> result = new LinkedHashSet<>(
                candidate.evidenceRefs()
        );
        if ("PROFITABILITY".equals(candidate.theme())) {
            input.facts().store().stream()
                    .filter(fact -> "MARGIN_PERCENT".equals(
                            fact.metricCode()
                    ))
                    .filter(fact -> directionMatches(
                            candidate.kind(), fact
                    ))
                    .filter(fact -> evidenceAvailable(
                            input, fact.evidenceRef()
                    ))
                    .map(WeeklyInterpretationInput.Fact::evidenceRef)
                    .forEach(result::add);
        }
        return List.copyOf(result);
    }

    private static Narrative contextualNarrative(
            CandidateSignal candidate,
            WeeklyInterpretationInput input
    ) {
        if ("PLAN".equals(candidate.theme())
                && candidate.kind() == CandidateKind.RISK
                && isCompletedMonth(input)) {
            return new Narrative(
                    "Выполнение плана",
                    "Завершившийся период закрыт существенно ниже "
                            + "целевого уровня выполнения плана."
            );
        }
        if ("REVENUE_DYNAMICS".equals(candidate.theme())) {
            if (candidate.kind() == CandidateKind.RISK
                    && candidateFacts(candidate, input).stream()
                    .anyMatch(WeeklyCandidateDisplayPolicy::isZeroAfterSales)) {
                return new Narrative(
                        "Динамика чистой выручки",
                        "Чистая выручка равна нулю после ненулевого "
                                + "значения прошлого периода."
                );
            }
            return directional(
                    "Динамика чистой выручки",
                    "Чистая выручка (продажи за вычетом возвратов) "
                            + "существенно снизилась относительно прошлого "
                            + "периода.",
                    "Чистая выручка (продажи за вычетом возвратов) "
                            + "существенно выросла относительно прошлого "
                            + "периода.",
                    candidate.kind()
            );
        }
        if ("PROFITABILITY".equals(candidate.theme())
                && input.facts().store().stream()
                .filter(fact -> "MARGIN_PERCENT".equals(fact.metricCode()))
                .anyMatch(fact -> directionMatches(candidate.kind(), fact))) {
            return directional(
                    "Динамика прибыльности",
                    "Валовая прибыль и маржинальность существенно снизились "
                            + "относительно прошлого периода.",
                    "Валовая прибыль и маржинальность существенно выросли "
                            + "относительно прошлого периода.",
                    candidate.kind()
            );
        }
        String label = candidate.categoryCode() == null
                ? null : input.manifest().categoryLabels().get(
                        candidate.categoryCode()
                );
        if (label == null || label.isBlank()) {
            return null;
        }
        String quoted = "«" + label + "»";
        return switch (candidate.theme()) {
            case "CATEGORY_MIX" -> directional(
                    "Динамика категории " + quoted,
                    "Выручка и доля категории " + quoted
                            + " существенно снизились.",
                    "Выручка и доля категории " + quoted
                            + " существенно выросли.",
                    candidate.kind()
            );
            case "ADDITIONAL_SALES" -> directional(
                    "Дополнительные продажи категории " + quoted,
                    "Выручка категории " + quoted
                            + " существенно снизилась.",
                    "Выручка категории " + quoted
                            + " существенно выросла.",
                    candidate.kind()
            );
            case "ATTACH_RATE" -> directional(
                    "Частота дополнительных продаж категории " + quoted,
                    "Частота дополнительных продаж категории " + quoted
                            + " существенно снизилась при достаточной базе.",
                    "Частота дополнительных продаж категории " + quoted
                            + " существенно выросла при достаточной базе.",
                    candidate.kind()
            );
            default -> null;
        };
    }

    private static List<WeeklyInterpretationInput.Fact> candidateFacts(
            CandidateSignal candidate,
            WeeklyInterpretationInput input
    ) {
        LinkedHashSet<String> references = new LinkedHashSet<>(
                candidate.evidenceRefs()
        );
        return java.util.stream.Stream.concat(
                        input.facts().store().stream(),
                        input.facts().employees().stream()
                                .flatMap(employee -> employee.facts().stream())
                )
                .filter(fact -> references.contains(fact.evidenceRef()))
                .toList();
    }

    private static boolean isCompletedMonth(
            WeeklyInterpretationInput input
    ) {
        var end = input.snapshot().period().end();
        return end.getDayOfMonth() == end.lengthOfMonth();
    }

    private static boolean isZeroAfterSales(
            WeeklyInterpretationInput.Fact fact
    ) {
        return "NET_REVENUE".equals(fact.metricCode())
                && number(fact.value()).compareTo(BigDecimal.ZERO) == 0
                && fact.comparison() != null
                && fact.comparison().previousValue() != null
                && fact.comparison().previousValue()
                .compareTo(BigDecimal.ZERO) > 0;
    }

    private static boolean directionMatches(
            CandidateKind kind,
            WeeklyInterpretationInput.Fact fact
    ) {
        if (fact.comparison() == null
                || fact.comparison().absoluteDelta() == null) {
            return false;
        }
        int direction = fact.comparison().absoluteDelta()
                .compareTo(BigDecimal.ZERO);
        return (kind == CandidateKind.RISK && direction < 0)
                || (kind == CandidateKind.OPPORTUNITY && direction > 0);
    }

    private static boolean evidenceAvailable(
            WeeklyInterpretationInput input,
            String evidenceRef
    ) {
        return input.manifest().evidence().stream()
                .anyMatch(evidence -> evidence.available()
                        && evidence.evidenceRef().equals(evidenceRef));
    }

    private static BigDecimal number(Object value) {
        return value instanceof BigDecimal decimal
                ? decimal : new BigDecimal(value.toString());
    }

    private static Narrative plan(CandidateKind kind) {
        return switch (kind) {
            case RISK -> new Narrative(
                    "Выполнение плана",
                    "Выполнение плана существенно ниже целевого уровня."
            );
            case OPPORTUNITY -> new Narrative(
                    "Выполнение плана",
                    "Выполнение плана выше целевого уровня."
            );
            case OBSERVATION -> new Narrative(
                    "Выполнение плана",
                    "Зафиксировано существенное изменение выполнения плана."
            );
        };
    }

    private static Narrative directional(
            String title,
            String risk,
            String opportunity,
            CandidateKind kind
    ) {
        return switch (kind) {
            case RISK -> new Narrative(title, risk);
            case OPPORTUNITY -> new Narrative(title, opportunity);
            case OBSERVATION -> new Narrative(
                    title, title + ": зафиксировано существенное изменение."
            );
        };
    }

    private static Narrative fallback(CandidateSignal candidate) {
        WeeklyCandidateNarrativePolicy.Narrative value =
                WeeklyCandidateNarrativePolicy.forCandidate(candidate);
        return new Narrative(value.title(), value.summary());
    }

    public record Narrative(String title, String summary) {
    }
}
