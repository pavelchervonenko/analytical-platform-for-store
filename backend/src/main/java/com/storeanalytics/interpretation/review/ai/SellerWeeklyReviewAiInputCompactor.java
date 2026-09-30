package com.storeanalytics.interpretation.review.ai;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.interpretation.review.WeeklyReviewV3Response;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.ReportState;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Effect;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Materiality;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Evidence;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Separate seller allowlist: legacy STORE-only guards remain intact. */
@Component
public final class SellerWeeklyReviewAiInputCompactor {
    public SellerWeeklyReviewAiInput compact(WeeklyReviewV3Response report) {
        var source = requireNonNull(report, "report");
        require(source.reportState() == ReportState.READY
                || source.reportState() == ReportState.PARTIAL,
                "Seller AI requires READY/PARTIAL deterministic facts");
        require(source.summary().outcome() != null, "Seller AI requires deterministic outcome");
        var factors = source.factors().stream().map(item -> new WeeklyReviewAiInput.FactorSource(
                item.factorId(), item.kind(), item.title(), item.comparison().direction().name(),
                item.effect().name(), item.contributionAmount() != null,
                item.effect() == Effect.POSITIVE
                        ? List.of("FACTOR_SIGNAL", "FACTOR_STRENGTH") : List.of("FACTOR_RISK", "FACTOR_CONTROL"),
                item.evidenceRefs())).toList();
        var actions = source.actions().stream().map(item -> {
            require("TEAM".equals(item.scope()) && item.employeePublicId() == null,
                    "Seller AI accepts only aggregate team actions");
            return new WeeklyReviewAiInput.ActionSource(item.actionId(), item.title(), item.check(),
                    item.evidenceRefs());
        }).toList();
        var effects = source.results().stream().filter(item -> "NET_REVENUE".equals(item.code())
                        || "GROSS_PROFIT".equals(item.code()))
                .filter(item -> item.materiality() == Materiality.MATERIAL)
                .map(item -> item.effect().name()).filter(item -> "POSITIVE".equals(item) || "NEGATIVE".equals(item))
                .distinct().toList();
        boolean positive = factors.stream().anyMatch(item -> "POSITIVE".equals(item.effect()));
        boolean negative = factors.stream().anyMatch(item -> "NEGATIVE".equals(item.effect()));
        var summary = new WeeklyReviewAiInput.SummarySource(effects.size() > 1 ? "MIXED"
                : effects.isEmpty() ? "NEUTRAL" : effects.getFirst(),
                List.of(positive && negative ? "SUMMARY_BALANCED" : negative ? "SUMMARY_RISK"
                        : positive ? "SUMMARY_STRENGTH" : "SUMMARY_OUTCOME"),
                factors.stream().map(WeeklyReviewAiInput.FactorSource::factorId).toList(),
                source.summary().outcome().evidenceRefs());
        Set<String> refs = new LinkedHashSet<>(summary.evidenceRefs());
        factors.forEach(item -> refs.addAll(item.evidenceRefs()));
        actions.forEach(item -> refs.addAll(item.evidenceRefs()));
        var indexed = new LinkedHashMap<String, Evidence>();
        source.evidence().forEach(item -> require(indexed.put(item.evidenceRef(), item) == null,
                "Seller evidence must be unique"));
        var evidence = refs.stream().map(ref -> {
            var item = indexed.get(ref);
            require(item != null && "SELLERS".equals(item.scope())
                    && item.employeePublicId() == null
                    && item.available() && ref.startsWith("SELLERS."),
                    "Seller AI requires available aggregate evidence");
            return new WeeklyReviewAiInput.EvidenceSource(ref, item.label(), item.unit().name(),
                    value(item.currentValue()), value(item.previousValue()));
        }).toList();
        return new SellerWeeklyReviewAiInput(SellerWeeklyReviewAiContract.INPUT_VERSION,
                SellerWeeklyReviewAiContract.PROMPT_VERSION, WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION,
                source.reportState().name(), 3, "SELLERS", summary, factors, actions, evidence);
    }

    private String value(Object value) {
        return value instanceof BigDecimal amount ? amount.toPlainString() : value == null ? null : value.toString();
    }
}
