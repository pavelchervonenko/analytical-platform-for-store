package com.storeanalytics.interpretation.review.ai;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Renders validated editorial choices into immutable user-facing schema4 text. */
@Component
public final class WeeklyReviewAiRendererV25 {

    public WeeklyReviewAiContent render(
            WeeklyReviewAiInput input,
            WeeklyReviewAiSelection selection
    ) {
        WeeklyReviewAiInput source = requireNonNull(input, "input");
        WeeklyReviewAiSelection choices = requireNonNull(
                selection, "selection"
        );
        Map<String, WeeklyReviewAiInput.FactorSource> factors = factorIndex(
                source.factors()
        );
        WeeklyReviewAiContent.Summary summary = new WeeklyReviewAiContent.Summary(
                summaryText(source, choices.summary(), factors),
                summaryEvidence(source, choices.summary(), factors)
        );
        Map<String, WeeklyReviewAiSelection.FactorSelection> factorChoices =
                selectionIndex(choices.factorSelections());
        List<WeeklyReviewAiContent.FactorExplanation> explanations =
                source.factors().stream()
                        .map(value -> factorExplanation(
                                value,
                                factorChoices.get(value.factorId())
                        ))
                        .toList();
        List<WeeklyReviewAiContent.ActionWording> actions =
                source.actions().stream()
                        .map(value -> new WeeklyReviewAiContent.ActionWording(
                                value.actionId(),
                                value.title(),
                                value.check()
                        ))
                        .toList();
        return new WeeklyReviewAiContent(
                WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION,
                summary,
                explanations,
                actions
        );
    }

    private String summaryText(
            WeeklyReviewAiInput input,
            WeeklyReviewAiSelection.SummarySelection selection,
            Map<String, WeeklyReviewAiInput.FactorSource> factors
    ) {
        String outcome = outcome(input.summary().outcomeEffect());
        String text = switch (selection.selector()) {
            case "SUMMARY_OUTCOME" -> outcome;
            case "SUMMARY_STRENGTH" -> outcome
                    + " Главный положительный сигнал — "
                    + summaryClause(factors.get(selection.primaryFactorId()))
                    + ".";
            case "SUMMARY_RISK" -> outcome
                    + " Главная зона внимания — "
                    + summaryClause(factors.get(selection.primaryFactorId()))
                    + ".";
            case "SUMMARY_BALANCED" -> outcome
                    + " Сильная сторона — "
                    + summaryClause(factors.get(selection.primaryFactorId()))
                    + "; зона внимания — "
                    + summaryClause(factors.get(selection.secondaryFactorId()))
                    + ".";
            default -> throw new IllegalArgumentException(
                    "Unknown summary selector"
            );
        };
        if ("PARTIAL".equals(input.reportState())) {
            return text + " Вывод основан только на доступной части данных.";
        }
        return text;
    }

    private String outcome(String effect) {
        return switch (effect) {
            case "POSITIVE" ->
                    "Неделя завершилась лучше периода сравнения.";
            case "NEGATIVE" ->
                    "Неделя завершилась слабее периода сравнения.";
            case "MIXED" ->
                    "Ключевые результаты недели изменились разнонаправленно.";
            case "NEUTRAL" ->
                    "Ключевые результаты недели существенно не изменились.";
            default -> throw new IllegalArgumentException(
                    "Unknown summary effect"
            );
        };
    }

    private String summaryClause(WeeklyReviewAiInput.FactorSource factor) {
        requireNonNull(factor, "summary factor");
        return "RETURN_CHANGE".equals(factor.kind())
                ? returnSummaryClause(factor)
                : lowercaseFirst(factor.title());
    }

    private WeeklyReviewAiContent.FactorExplanation factorExplanation(
            WeeklyReviewAiInput.FactorSource factor,
            WeeklyReviewAiSelection.FactorSelection selection
    ) {
        requireNonNull(selection, "factor selection");
        String signal = factorSignal(factor);
        String interpretation = switch (selection.selector()) {
            case "FACTOR_SIGNAL" -> "Это положительный сигнал.";
            case "FACTOR_STRENGTH" -> "Это сильная сторона недели.";
            case "FACTOR_RISK" -> "Это риск недели.";
            case "FACTOR_CONTROL" -> "Это отдельная зона контроля.";
            default -> throw new IllegalArgumentException(
                    "Unknown factor selector"
            );
        };
        return new WeeklyReviewAiContent.FactorExplanation(
                factor.factorId(),
                signal + " " + interpretation,
                factor.evidenceRefs()
        );
    }

    private String factorSignal(WeeklyReviewAiInput.FactorSource factor) {
        return "RETURN_CHANGE".equals(factor.kind())
                ? returnFactorSignal(factor)
                : factor.title() + " относительно периода сравнения.";
    }

    private String returnSummaryClause(
            WeeklyReviewAiInput.FactorSource factor
    ) {
        return switch (factor.direction()) {
            case "UP" -> factor.causalLanguageAllowed()
                    ? "давление возвратов на результат усилилось"
                    : "сумма возвратов стала выше";
            case "DOWN" -> factor.causalLanguageAllowed()
                    ? "давление возвратов на результат снизилось"
                    : "сумма возвратов стала ниже";
            case "FLAT" -> factor.causalLanguageAllowed()
                    ? "давление возвратов на результат существенно "
                            + "не изменилось"
                    : "сумма возвратов существенно не изменилась";
            case "UNKNOWN" -> "изменение суммы возвратов недоступно";
            default -> throw new IllegalArgumentException(
                    "Unknown factor direction"
            );
        };
    }

    private String returnFactorSignal(
            WeeklyReviewAiInput.FactorSource factor
    ) {
        return switch (factor.direction()) {
            case "UP", "DOWN", "FLAT" ->
                    capitalizeFirst(returnSummaryClause(factor))
                            + " относительно периода сравнения.";
            case "UNKNOWN" ->
                    "Изменение суммы возвратов относительно периода "
                            + "сравнения недоступно.";
            default -> throw new IllegalArgumentException(
                    "Unknown factor direction"
            );
        };
    }

    private String lowercaseFirst(String value) {
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    private String capitalizeFirst(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private List<String> summaryEvidence(
            WeeklyReviewAiInput input,
            WeeklyReviewAiSelection.SummarySelection selection,
            Map<String, WeeklyReviewAiInput.FactorSource> factors
    ) {
        Set<String> result = new LinkedHashSet<>(
                input.summary().evidenceRefs()
        );
        appendFactorEvidence(result, factors, selection.primaryFactorId());
        appendFactorEvidence(result, factors, selection.secondaryFactorId());
        require(result.size() <= 10,
                "rendered summary evidence exceeds schema limit");
        return List.copyOf(result);
    }

    private void appendFactorEvidence(
            Set<String> target,
            Map<String, WeeklyReviewAiInput.FactorSource> factors,
            String factorId
    ) {
        if (factorId != null) {
            target.addAll(requireNonNull(
                    factors.get(factorId), "summary factor"
            ).evidenceRefs());
        }
    }

    private Map<String, WeeklyReviewAiInput.FactorSource> factorIndex(
            List<WeeklyReviewAiInput.FactorSource> factors
    ) {
        Map<String, WeeklyReviewAiInput.FactorSource> result =
                new LinkedHashMap<>();
        factors.forEach(value -> result.put(value.factorId(), value));
        return result;
    }

    private Map<String, WeeklyReviewAiSelection.FactorSelection> selectionIndex(
            List<WeeklyReviewAiSelection.FactorSelection> selections
    ) {
        Map<String, WeeklyReviewAiSelection.FactorSelection> result =
                new LinkedHashMap<>();
        selections.forEach(value -> result.put(value.factorId(), value));
        return result;
    }
}
