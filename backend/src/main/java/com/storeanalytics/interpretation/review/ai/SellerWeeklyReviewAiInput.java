package com.storeanalytics.interpretation.review.ai;

import static com.storeanalytics.common.validation.ModelValidation.require;

import java.util.List;

/** No roster, seller IDs/names, hashes, raw rows or personal actions cross the provider boundary. */
public record SellerWeeklyReviewAiInput(
        int contractVersion, String promptVersion, int contentSchemaVersion, String reportState,
        int reportContractVersion, String scope, WeeklyReviewAiInput.SummarySource summary,
        List<WeeklyReviewAiInput.FactorSource> factors, List<WeeklyReviewAiInput.ActionSource> actions,
        List<WeeklyReviewAiInput.EvidenceSource> evidence
) implements WeeklyReviewAiEditorialInput {
    public SellerWeeklyReviewAiInput {
        require(contractVersion == SellerWeeklyReviewAiContract.INPUT_VERSION, "Seller input must use schema 5");
        require(SellerWeeklyReviewAiContract.PROMPT_VERSION.equals(promptVersion), "Seller input must use prompt v26");
        require(reportContractVersion == 3 && "SELLERS".equals(scope), "Seller input must target report v3/SELLERS");
        require(contentSchemaVersion == WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION,
                "Seller input requires content schema 4");
        require("READY".equals(reportState) || "PARTIAL".equals(reportState), "Seller report must be available");
        require(summary != null, "Seller input requires summary");
        factors = List.copyOf(factors);
        actions = List.copyOf(actions);
        evidence = List.copyOf(evidence);
        require(factors.size() <= 3 && actions.size() <= 3 && evidence.size() <= 64, "Seller input must be bounded");
        var factorIds = factors.stream().map(WeeklyReviewAiInput.FactorSource::factorId)
                .collect(java.util.stream.Collectors.toSet());
        require(factorIds.size() == factors.size() && factorIds.containsAll(summary.allowedFocusFactorIds()),
                "Seller factor identities must be unique and resolve");
        require(actions.stream().map(WeeklyReviewAiInput.ActionSource::actionId).distinct().count() == actions.size(),
                "Seller action identities must be unique");
        require(!evidence.isEmpty(), "Seller input requires aggregate evidence");
        var references = evidence.stream().map(WeeklyReviewAiInput.EvidenceSource::evidenceRef)
                .collect(java.util.stream.Collectors.toSet());
        require(references.size() == evidence.size(), "Seller evidence must be unique");
        require(references.stream().allMatch(ref -> ref.startsWith("SELLERS.")),
                "Seller AI evidence must be aggregate seller facts");
        require(references.containsAll(summary.evidenceRefs()), "Seller summary evidence must resolve");
        factors.forEach(item -> require(references.containsAll(item.evidenceRefs()), "Factor evidence must resolve"));
        actions.forEach(item -> require(references.containsAll(item.evidenceRefs()), "Action evidence must resolve"));
    }
}
