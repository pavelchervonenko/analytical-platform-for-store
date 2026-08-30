package com.storeanalytics.interpretation.review.ai;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Action;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Evidence;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Factor;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Materiality;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricComparison;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricState;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.ReportState;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Projects a deterministic report into bounded facts and editorial choices. */
@Component
public final class WeeklyReviewAiInputCompactor {

    public WeeklyReviewAiInput compact(WeeklyReviewResponse response) {
        WeeklyReviewResponse source = requireNonNull(response, "response");
        require(source.reportState() == ReportState.READY
                        || source.reportState() == ReportState.PARTIAL,
                "AI input requires a READY or PARTIAL report");
        require(source.summary() != null && source.summary().outcome() != null,
                "AI input requires a deterministic summary outcome");

        Map<String, Evidence> indexedEvidence = index(source.evidence());
        List<WeeklyReviewAiInput.FactorSource> factors = source.factors().stream()
                .map(this::factor)
                .toList();
        List<WeeklyReviewAiInput.ActionSource> actions = source.actions().stream()
                .map(this::action)
                .toList();
        WeeklyReviewAiInput.SummarySource summary = summary(source, factors);
        Set<String> references = new LinkedHashSet<>(summary.evidenceRefs());
        factors.forEach(value -> references.addAll(value.evidenceRefs()));
        actions.forEach(value -> references.addAll(value.evidenceRefs()));

        return new WeeklyReviewAiInput(
                WeeklyReviewAiContract.INPUT_SCHEMA_VERSION,
                WeeklyReviewAiContract.PROMPT_VERSION,
                WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION,
                source.reportState().name(),
                summary,
                factors,
                actions,
                evidence(indexedEvidence, references)
        );
    }

    private WeeklyReviewAiInput.SummarySource summary(
            WeeklyReviewResponse response,
            List<WeeklyReviewAiInput.FactorSource> factors
    ) {
        boolean hasPositive = factors.stream()
                .anyMatch(value -> "POSITIVE".equals(value.effect()));
        boolean hasNegative = factors.stream()
                .anyMatch(value -> "NEGATIVE".equals(value.effect()));
        List<String> selectors;
        if (hasPositive && hasNegative) {
            selectors = List.of("SUMMARY_BALANCED");
        } else if (hasNegative) {
            selectors = List.of("SUMMARY_RISK");
        } else if (hasPositive) {
            selectors = List.of("SUMMARY_STRENGTH");
        } else {
            selectors = List.of("SUMMARY_OUTCOME");
        }
        return new WeeklyReviewAiInput.SummarySource(
                summaryOutcomeEffect(response),
                selectors,
                factors.stream()
                        .map(WeeklyReviewAiInput.FactorSource::factorId)
                        .toList(),
                response.summary().outcome().evidenceRefs()
        );
    }

    private String summaryOutcomeEffect(WeeklyReviewResponse response) {
        List<String> materialEffects = response.results().stream()
                .filter(metric -> "NET_REVENUE".equals(metric.code())
                        || "GROSS_PROFIT".equals(metric.code()))
                .filter(metric -> metric.metricState() != MetricState.UNAVAILABLE)
                .filter(metric -> metric.materiality() == Materiality.MATERIAL)
                .map(MetricComparison::effect)
                .filter(effect -> effect == WeeklyReviewResponse.Effect.POSITIVE
                        || effect == WeeklyReviewResponse.Effect.NEGATIVE)
                .map(Enum::name)
                .distinct()
                .toList();
        if (materialEffects.size() > 1) {
            return "MIXED";
        }
        return materialEffects.isEmpty() ? "NEUTRAL" : materialEffects.getFirst();
    }

    private WeeklyReviewAiInput.FactorSource factor(Factor factor) {
        Factor source = requireNonNull(factor, "factor");
        return new WeeklyReviewAiInput.FactorSource(
                source.factorId(),
                source.kind(),
                source.title(),
                source.comparison().direction().name(),
                source.effect().name(),
                source.contributionAmount() != null,
                "POSITIVE".equals(source.effect().name())
                        ? List.of("FACTOR_SIGNAL", "FACTOR_STRENGTH")
                        : List.of("FACTOR_RISK", "FACTOR_CONTROL"),
                source.evidenceRefs()
        );
    }

    private WeeklyReviewAiInput.ActionSource action(Action action) {
        Action source = requireNonNull(action, "action");
        require("STORE".equals(source.scope())
                        && source.employeePublicId() == null,
                "AI input accepts only store actions");
        return new WeeklyReviewAiInput.ActionSource(
                source.actionId(),
                source.title(),
                source.check(),
                source.evidenceRefs()
        );
    }

    private Map<String, Evidence> index(List<Evidence> values) {
        Map<String, Evidence> result = new LinkedHashMap<>();
        values.forEach(value -> {
            Evidence previous = result.put(value.evidenceRef(), value);
            require(previous == null,
                    "Weekly review evidence references must be unique");
        });
        return result;
    }

    private List<WeeklyReviewAiInput.EvidenceSource> evidence(
            Map<String, Evidence> indexed,
            Set<String> selected
    ) {
        List<WeeklyReviewAiInput.EvidenceSource> result = new ArrayList<>();
        for (String reference : selected) {
            Evidence value = indexed.get(reference);
            require(value != null, "AI input evidence reference must resolve");
            require("STORE".equals(value.scope())
                            && value.employeePublicId() == null,
                    "AI input accepts only store evidence");
            require(value.available(), "AI input accepts only available evidence");
            result.add(new WeeklyReviewAiInput.EvidenceSource(
                    value.evidenceRef(),
                    value.label(),
                    value.unit().name(),
                    value(value.currentValue()),
                    value(value.previousValue())
            ));
        }
        return List.copyOf(result);
    }

    private String value(Object source) {
        if (source instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        return source == null ? null : source.toString();
    }
}
