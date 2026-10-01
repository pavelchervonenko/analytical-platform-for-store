package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse.CoverageState;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.ReportState;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.SalesStructureBlock;
import com.storeanalytics.metrics.service.SellerPeriodFacts;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Composes seller-only deterministic facts before the versioned v3 response is available. */
@Component
final class SellerWeeklyReviewProjector {

    private final WeeklyReviewQualityPolicyV1 quality = new WeeklyReviewQualityPolicyV1();
    private final WeeklyReviewCoreProjector core = new WeeklyReviewCoreProjector();
    private final WeeklyReviewStructureProjector structure =
            new WeeklyReviewStructureProjector(new WeeklyReviewPolicyV1());
    private final SellerWeeklyAdditionalSalesProjector additional =
            new SellerWeeklyAdditionalSalesProjector();
    private final SellerWeeklyTeamFactsProjector team = new SellerWeeklyTeamFactsProjector();

    Projection project(SellerWeeklyReviewFacts facts) {
        SellerWeeklyReviewFacts source = requireNonNull(facts, "facts");
        SellerPeriodFacts current = source.comparison().current();
        SellerPeriodFacts previous = source.comparison().previous();
        WeeklyReviewQualityPolicyV1.Decision decision = quality.decideSellers(
                source.sourceCoverage(), current.metrics(), previous.metrics(),
                new WeeklyReviewQualityPolicyV1.AttributionWindows(
                        current.returnAttribution(), previous.returnAttribution()),
                source.period().current(), source.period().previous(),
                source.sourceStability());
        boolean blocked = decision.reportState() == ReportState.BLOCKED;
        boolean returnAttributionComplete = current.returnAttribution().complete()
                && previous.returnAttribution().complete();
        boolean revenueCoverageComplete = decision.sourceCoverage().stream()
                .filter(item -> item.requiredForReport())
                .allMatch(item -> item.state() == CoverageState.COMPLETE);
        WeeklyReviewCoreProjector.Projection results = core.projectSellers(
                current, previous, blocked, revenueCoverageComplete, returnAttributionComplete);
        SalesStructureBlock salesStructure = blocked ? structure.unavailableSellers()
                : structure.projectSellers(current, previous, revenueCoverageComplete,
                        returnAttributionComplete);
        boolean categoryQualityComplete = current.metrics().unmappedItemCount() == 0
                && previous.metrics().unmappedItemCount() == 0 && revenueCoverageComplete
                && returnAttributionComplete;
        SellerWeeklyAdditionalSalesProjector.Projection additionalSales = additional.project(
                current, previous, blocked, categoryQualityComplete);
        Optional<SellerWeeklyTeamFactsProjector.TeamFinancialFacts> teamFacts = blocked
                ? Optional.empty() : Optional.of(team.project(source.comparison()));
        return new Projection(decision, results, salesStructure, additionalSales,
                teamFacts, returnAttributionComplete);
    }

    record Projection(
            WeeklyReviewQualityPolicyV1.Decision quality,
            WeeklyReviewCoreProjector.Projection core,
            SalesStructureBlock structure,
            SellerWeeklyAdditionalSalesProjector.Projection additionalSales,
            Optional<SellerWeeklyTeamFactsProjector.TeamFinancialFacts> teamFacts,
            boolean returnAttributionComplete
    ) {
    }
}
