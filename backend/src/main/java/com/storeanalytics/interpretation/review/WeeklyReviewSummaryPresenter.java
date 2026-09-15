package com.storeanalytics.interpretation.review;

import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.BlockState.INSUFFICIENT;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.BlockState.LIMITED;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.BlockState.READY;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.Effect.NEGATIVE;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.Effect.POSITIVE;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.GeneratedBy.DETERMINISTIC;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.Materiality.MATERIAL;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse.BlockState;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Effect;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Factor;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricComparison;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricState;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.NarrativeItem;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.ReportState;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.SummaryBlock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Builds the single deterministic management summary presented by every client. */
final class WeeklyReviewSummaryPresenter {

    SummaryBlock present(
            ReportState reportState,
            List<MetricComparison> results,
            List<Factor> factors
    ) {
        if (reportState == ReportState.BLOCKED) {
            return new SummaryBlock(
                    "summary",
                    INSUFFICIENT,
                    null,
                    null,
                    null,
                    DETERMINISTIC
            );
        }

        MetricComparison revenue = metric(results, "NET_REVENUE");
        Factor primaryFactor = primaryFactor(factors);
        Outcome outcome = outcome(results);
        Set<String> evidence = new LinkedHashSet<>();
        results.stream()
                .filter(metric -> metric.metricState() != MetricState.UNAVAILABLE)
                .forEach(metric -> evidence.addAll(metric.evidenceRefs()));
        if (primaryFactor != null) {
            evidence.addAll(primaryFactor.evidenceRefs());
        }

        NarrativeItem positive = factors.stream()
                .filter(factor -> factor.effect() == POSITIVE)
                .findFirst()
                .map(factor -> narrative("summary:positive", factor))
                .orElse(null);
        NarrativeItem risk = factors.stream()
                .filter(factor -> factor.effect() == NEGATIVE)
                .findFirst()
                .map(factor -> narrative("summary:risk", factor))
                .orElse(null);
        boolean includedResultsAreReady = results.stream()
                .allMatch(metric -> metric.metricState() == MetricState.READY
                        || metric.metricState() == MetricState.UNAVAILABLE);
        BlockState state = revenue.metricState() == MetricState.READY && includedResultsAreReady
                ? READY
                : LIMITED;

        return new SummaryBlock(
                "summary",
                state,
                new NarrativeItem(
                        "summary:outcome",
                        summaryText(outcome, primaryFactor),
                        outcome.effect(),
                        List.copyOf(evidence)
                ),
                positive,
                risk,
                DETERMINISTIC
        );
    }

    private Outcome outcome(List<MetricComparison> results) {
        List<Effect> materialEffects = results.stream()
                .filter(metric -> metric.metricState() == MetricState.READY)
                .filter(metric -> metric.materiality() == MATERIAL)
                .map(MetricComparison::effect)
                .filter(effect -> effect == POSITIVE || effect == NEGATIVE)
                .distinct()
                .toList();
        if (materialEffects.size() > 1) {
            return Outcome.MIXED;
        }
        if (materialEffects.isEmpty()) {
            return Outcome.NEUTRAL;
        }
        return materialEffects.getFirst() == POSITIVE ? Outcome.POSITIVE : Outcome.NEGATIVE;
    }

    private String summaryText(Outcome result, Factor primaryFactor) {
        String outcome = switch (result) {
            case POSITIVE -> "Неделя завершилась лучше периода сравнения.";
            case NEGATIVE -> "Неделя завершилась слабее периода сравнения.";
            case MIXED -> "Ключевые результаты недели изменились разнонаправленно.";
            case NEUTRAL -> "Ключевые результаты недели существенно не изменились.";
        };
        if (primaryFactor == null) {
            return outcome;
        }
        String prefix = primaryFactor.effect() == NEGATIVE
                ? " Главная зона внимания — "
                : " Главный положительный сигнал — ";
        return outcome + prefix + lowercaseFirst(primaryFactor.title()) + ".";
    }

    private Factor primaryFactor(List<Factor> factors) {
        return factors.stream()
                .filter(factor -> factor.effect() == NEGATIVE)
                .findFirst()
                .or(() -> factors.stream()
                        .filter(factor -> factor.effect() == POSITIVE)
                        .findFirst())
                .orElse(null);
    }

    private NarrativeItem narrative(String itemId, Factor factor) {
        return new NarrativeItem(
                itemId,
                factor.detail(),
                factor.effect(),
                factor.evidenceRefs()
        );
    }

    private MetricComparison metric(List<MetricComparison> metrics, String code) {
        return metrics.stream()
                .filter(metric -> code.equals(metric.code()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Missing metric: " + code));
    }

    private String lowercaseFirst(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return value.substring(0, 1).toLowerCase(Locale.forLanguageTag("ru-RU"))
                + value.substring(1);
    }

    private enum Outcome {
        POSITIVE(WeeklyReviewResponse.Effect.POSITIVE),
        NEGATIVE(WeeklyReviewResponse.Effect.NEGATIVE),
        MIXED(WeeklyReviewResponse.Effect.NEUTRAL),
        NEUTRAL(WeeklyReviewResponse.Effect.NEUTRAL);

        private final Effect effect;

        Outcome(Effect effect) {
            this.effect = effect;
        }

        Effect effect() {
            return effect;
        }
    }
}
