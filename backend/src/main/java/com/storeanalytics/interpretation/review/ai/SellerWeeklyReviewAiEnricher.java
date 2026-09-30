package com.storeanalytics.interpretation.review.ai;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse.AiEnhancement;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.AiState;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Factor;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.GeneratedBy;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.NarrativeItem;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.ReportState;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.SummaryBlock;
import com.storeanalytics.interpretation.review.WeeklyReviewV3Response;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Applies only wording bound to the exact seller input; never changes backend-owned facts. */
@Component
public final class SellerWeeklyReviewAiEnricher {
    private final SellerWeeklyReviewAiInputCompactor compactor;
    private final WeeklyReviewAiContentCodec codec;

    public SellerWeeklyReviewAiEnricher(SellerWeeklyReviewAiInputCompactor compactor,
                                       WeeklyReviewAiContentCodec codec) {
        this.compactor = compactor;
        this.codec = codec;
    }

    public Optional<WeeklyReviewV3Response> applyIfCompatible(
            WeeklyReviewV3Response source, PersistedWeeklyReviewAiEnrichment enrichment) {
        if (!SellerWeeklyReviewAiContract.isActive(enrichment.promptVersion(), enrichment.contentSchemaVersion())
                || source.summary().outcome() == null
                || (source.reportState() != ReportState.READY && source.reportState() != ReportState.PARTIAL)
                || !codec.hash(codec.canonical(compactor.compact(source))).equals(enrichment.inputHash())) {
            return Optional.empty();
        }
        WeeklyReviewAiContent content = enrichment.content();
        if (!codec.hash(codec.canonical(content)).equals(enrichment.contentHash())
                || source.factors().size() != content.factorExplanations().size()
                || source.actions().size() != content.actionWordings().size()) {
            return Optional.empty();
        }
        List<String> baseRefs = source.summary().outcome().evidenceRefs();
        List<String> actualRefs = content.summary().evidenceRefs();
        var allowedRefs = new LinkedHashSet<>(baseRefs);
        source.factors().forEach(factor -> allowedRefs.addAll(factor.evidenceRefs()));
        if (actualRefs.size() < baseRefs.size()
                || !actualRefs.subList(0, baseRefs.size()).equals(baseRefs)
                || new LinkedHashSet<>(actualRefs).size() != actualRefs.size()
                || !allowedRefs.containsAll(actualRefs)) {
            return Optional.empty();
        }
        List<Factor> factors = new ArrayList<>();
        for (int index = 0; index < source.factors().size(); index++) {
            Factor factor = source.factors().get(index);
            var wording = content.factorExplanations().get(index);
            if (!factor.factorId().equals(wording.factorId())
                    || !factor.evidenceRefs().equals(wording.evidenceRefs())) {
                return Optional.empty();
            }
            factors.add(new Factor(factor.factorId(), factor.kind(), factor.title(), wording.text(),
                    factor.comparison(), factor.contributionAmount(), factor.effect(), factor.evidenceRefs()));
        }
        for (int index = 0; index < source.actions().size(); index++) {
            var action = source.actions().get(index);
            var wording = content.actionWordings().get(index);
            if (!action.actionId().equals(wording.actionId()) || !action.title().equals(wording.title())
                    || !action.check().equals(wording.check())) {
                return Optional.empty();
            }
        }
        var summary = source.summary();
        var outcome = summary.outcome();
        var enhancedSummary = new SummaryBlock(summary.blockId(), summary.state(),
                new NarrativeItem(outcome.itemId(), content.summary().text(), outcome.effect(), actualRefs),
                summary.positive(), summary.risk(), GeneratedBy.AI_ENHANCED);
        return Optional.of(new WeeklyReviewV3Response(source.contractVersion(), source.versions(), source.period(),
                source.provenance(), source.reportState(), source.qualitySummary(),
                source.sourceCoverage(), source.scope(),
                source.membership(), source.sourceIdentityHash(), enhancedSummary, source.results(),
                source.revenueDecomposition(), source.additionalSales(), List.copyOf(factors), source.salesStructure(),
                source.team(), source.teamDisplay(), source.employees(), source.actions(), source.limitations(),
                source.evidence(), new AiEnhancement(AiState.READY, enrichment.promptVersion(),
                        enrichment.contentSchemaVersion(), enrichment.publishedAt())));
    }
}
