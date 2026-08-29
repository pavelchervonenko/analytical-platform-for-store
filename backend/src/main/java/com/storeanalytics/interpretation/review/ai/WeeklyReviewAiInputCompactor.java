package com.storeanalytics.interpretation.review.ai;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Action;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Evidence;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Factor;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.NarrativeItem;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.ReportState;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Projects a deterministic report into a bounded store-only provider payload. */
@Component
public final class WeeklyReviewAiInputCompactor {

    private static final Pattern NUMERIC_LITERAL = Pattern.compile(
            "(?<![\\p{L}\\p{N}])[+-]?\\d+(?:[\\s\\u00A0]\\d{3})*(?:[.,]\\d+)?"
    );

    public WeeklyReviewAiInput compact(WeeklyReviewResponse response) {
        WeeklyReviewResponse source = requireNonNull(response, "response");
        require(source.reportState() == ReportState.READY
                        || source.reportState() == ReportState.PARTIAL,
                "AI input requires a READY or PARTIAL report");
        require(source.summary() != null && source.summary().outcome() != null,
                "AI input requires a deterministic summary outcome");

        Map<String, Evidence> indexedEvidence = index(source.evidence());
        List<WeeklyReviewAiInput.FactorSource> factors = source.factors().stream()
                .map(value -> factor(value, indexedEvidence))
                .toList();
        List<WeeklyReviewAiInput.ActionSource> actions = source.actions().stream()
                .map(value -> action(value, indexedEvidence))
                .toList();
        WeeklyReviewAiInput.SummarySource summary = summary(
                source, indexedEvidence
        );
        Set<String> references = new LinkedHashSet<>(summary.evidenceRefs());
        factors.forEach(value -> references.addAll(value.evidenceRefs()));
        actions.forEach(value -> references.addAll(value.evidenceRefs()));

        return new WeeklyReviewAiInput(
                WeeklyReviewAiContract.INPUT_SCHEMA_VERSION,
                WeeklyReviewAiContract.PROMPT_VERSION,
                WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION,
                summary,
                factors,
                actions,
                evidence(indexedEvidence, references)
        );
    }

    private WeeklyReviewAiInput.SummarySource summary(
            WeeklyReviewResponse response,
            Map<String, Evidence> evidence
    ) {
        NarrativeItem outcome = response.summary().outcome();
        return new WeeklyReviewAiInput.SummarySource(
                outcome.text(),
                outcome.evidenceRefs(),
                numericLiterals(
                        List.of(outcome.text()),
                        outcome.evidenceRefs(),
                        evidence
                )
        );
    }

    private WeeklyReviewAiInput.FactorSource factor(
            Factor factor,
            Map<String, Evidence> evidence
    ) {
        Factor source = requireNonNull(factor, "factor");
        return new WeeklyReviewAiInput.FactorSource(
                source.factorId(),
                source.title(),
                source.detail(),
                source.effect().name(),
                source.contributionAmount() != null,
                source.evidenceRefs(),
                numericLiterals(
                        List.of(source.title(), source.detail()),
                        source.evidenceRefs(),
                        evidence
                )
        );
    }

    private WeeklyReviewAiInput.ActionSource action(
            Action action,
            Map<String, Evidence> evidence
    ) {
        Action source = requireNonNull(action, "action");
        require("STORE".equals(source.scope())
                        && source.employeePublicId() == null,
                "AI input accepts only store actions");
        return new WeeklyReviewAiInput.ActionSource(
                source.actionId(),
                source.title(),
                source.check(),
                source.evidenceRefs(),
                numericLiterals(
                        List.of(source.title(), source.check()),
                        source.evidenceRefs(),
                        evidence
                )
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

    private List<String> numericLiterals(
            List<String> texts,
            List<String> evidenceRefs,
            Map<String, Evidence> evidence
    ) {
        Set<String> result = new LinkedHashSet<>();
        texts.forEach(text -> addNumericLiterals(result, text));
        for (String reference : evidenceRefs) {
            Evidence value = evidence.get(reference);
            require(value != null, "AI input evidence reference must resolve");
            addNumericLiterals(result, this.value(value.currentValue()));
            addNumericLiterals(result, this.value(value.previousValue()));
        }
        return List.copyOf(result);
    }

    private void addNumericLiterals(Set<String> target, String text) {
        if (text == null) {
            return;
        }
        Matcher matcher = NUMERIC_LITERAL.matcher(text);
        while (matcher.find()) {
            target.add(matcher.group());
        }
    }
}
