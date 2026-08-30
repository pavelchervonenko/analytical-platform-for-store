package com.storeanalytics.interpretation.review.ai;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Action;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.AiEnhancement;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.AiState;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Factor;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.GeneratedBy;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.NarrativeItem;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.ReportState;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.SummaryBlock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Applies validated wording while preserving every backend-owned fact and
 * returning the deterministic report unchanged on any contract mismatch.
 */
@Component
public final class WeeklyReviewAiEnricher {

    public WeeklyReviewResponse apply(
            WeeklyReviewResponse response,
            WeeklyReviewAiValidationResult validation,
            Instant publishedAt
    ) {
        return apply(
                response, validation, publishedAt,
                WeeklyReviewAiContract.PROMPT_VERSION,
                WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION
        );
    }

    public WeeklyReviewResponse apply(
            WeeklyReviewResponse response,
            WeeklyReviewAiValidationResult validation,
            Instant publishedAt,
            String promptVersion,
            int contentSchemaVersion
    ) {
        WeeklyReviewResponse source = requireNonNull(response, "response");
        WeeklyReviewAiValidationResult result = requireNonNull(
                validation, "validation"
        );
        Instant published = requireNonNull(publishedAt, "publishedAt");
        String prompt = requireNonNull(promptVersion, "promptVersion");
        if (!WeeklyReviewAiContract.isReadable(prompt, contentSchemaVersion)
                || !result.semanticValidated()
                || result.content() == null
                || !matches(source, result.content(), prompt)) {
            return source;
        }
        WeeklyReviewAiContent content = result.content();
        return new WeeklyReviewResponse(
                source.contractVersion(),
                source.versions(),
                source.period(),
                source.provenance(),
                source.reportState(),
                source.qualitySummary(),
                source.sourceCoverage(),
                summary(source.summary(), content.summary()),
                source.results(),
                source.revenueDecomposition(),
                factors(source.factors(), content.factorExplanations()),
                source.salesStructure(),
                source.team(),
                source.employees(),
                actions(source.actions(), content.actionWordings(), prompt),
                source.limitations(),
                source.evidence(),
                new AiEnhancement(
                        AiState.READY,
                        prompt,
                        contentSchemaVersion,
                        published
                )
        );
    }

    public Optional<WeeklyReviewResponse> applyIfCompatible(
            WeeklyReviewResponse response,
            WeeklyReviewAiValidationResult validation,
            Instant publishedAt,
            String promptVersion,
            int contentSchemaVersion
    ) {
        WeeklyReviewResponse source = requireNonNull(response, "response");
        WeeklyReviewResponse applied = apply(
                source, validation, publishedAt,
                promptVersion, contentSchemaVersion
        );
        return applied == source ? Optional.empty() : Optional.of(applied);
    }

    private boolean matches(
            WeeklyReviewResponse response,
            WeeklyReviewAiContent content,
            String promptVersion
    ) {
        if ((response.reportState() != ReportState.READY
                && response.reportState() != ReportState.PARTIAL)
                || response.summary().outcome() == null
                || content.schemaVersion()
                        != WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION
                || !summaryEvidenceMatches(
                        response, content, promptVersion
                )
                || response.factors().size()
                        != content.factorExplanations().size()
                || response.actions().size() != content.actionWordings().size()) {
            return false;
        }
        for (int index = 0; index < response.factors().size(); index++) {
            Factor source = response.factors().get(index);
            WeeklyReviewAiContent.FactorExplanation wording =
                    content.factorExplanations().get(index);
            if (!source.factorId().equals(wording.factorId())
                    || !source.evidenceRefs().equals(wording.evidenceRefs())) {
                return false;
            }
        }
        for (int index = 0; index < response.actions().size(); index++) {
            Action source = response.actions().get(index);
            WeeklyReviewAiContent.ActionWording wording =
                    content.actionWordings().get(index);
            if (!"STORE".equals(source.scope())
                    || source.employeePublicId() != null
                    || !source.actionId().equals(wording.actionId())
                    || !source.check().equals(wording.check())
                    || (WeeklyReviewAiContract.hasBackendOwnedActionTitle(
                            promptVersion
                    ) && !source.title().equals(wording.title()))) {
                return false;
            }
        }
        return true;
    }

    private boolean summaryEvidenceMatches(
            WeeklyReviewResponse response,
            WeeklyReviewAiContent content,
            String promptVersion
    ) {
        List<String> source = response.summary().outcome().evidenceRefs();
        List<String> actual = content.summary().evidenceRefs();
        if (!WeeklyReviewAiContract.PROMPT_VERSION.equals(promptVersion)) {
            return source.equals(actual);
        }
        if (actual.size() < source.size()
                || !actual.subList(0, source.size()).equals(source)
                || new LinkedHashSet<>(actual).size() != actual.size()) {
            return false;
        }
        Set<String> allowed = new LinkedHashSet<>(source);
        response.factors().forEach(
                factor -> allowed.addAll(factor.evidenceRefs())
        );
        return allowed.containsAll(actual);
    }

    private SummaryBlock summary(
            SummaryBlock source,
            WeeklyReviewAiContent.Summary wording
    ) {
        NarrativeItem outcome = source.outcome();
        return new SummaryBlock(
                source.blockId(),
                source.state(),
                new NarrativeItem(
                        outcome.itemId(),
                        wording.text(),
                        outcome.effect(),
                        wording.evidenceRefs()
                ),
                source.positive(),
                source.risk(),
                GeneratedBy.AI_ENHANCED
        );
    }

    private List<Factor> factors(
            List<Factor> source,
            List<WeeklyReviewAiContent.FactorExplanation> wordings
    ) {
        List<Factor> result = new ArrayList<>(source.size());
        for (int index = 0; index < source.size(); index++) {
            Factor factor = source.get(index);
            result.add(new Factor(
                    factor.factorId(),
                    factor.kind(),
                    factor.title(),
                    wordings.get(index).text(),
                    factor.comparison(),
                    factor.contributionAmount(),
                    factor.effect(),
                    factor.evidenceRefs()
            ));
        }
        return List.copyOf(result);
    }

    private List<Action> actions(
            List<Action> source,
            List<WeeklyReviewAiContent.ActionWording> wordings,
            String promptVersion
    ) {
        List<Action> result = new ArrayList<>(source.size());
        for (int index = 0; index < source.size(); index++) {
            Action action = source.get(index);
            result.add(new Action(
                    action.actionId(),
                    action.priority(),
                    action.actionType(),
                    action.scope(),
                    action.employeePublicId(),
                    WeeklyReviewAiContract.hasBackendOwnedActionTitle(
                            promptVersion
                    ) ? action.title() : wordings.get(index).title(),
                    action.metricCode(),
                    action.target(),
                    action.check(),
                    action.horizon(),
                    GeneratedBy.AI_ENHANCED,
                    action.evidenceRefs()
            ));
        }
        return List.copyOf(result);
    }
}
