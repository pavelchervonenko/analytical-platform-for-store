package com.storeanalytics.interpretation.review.ai;

import java.util.List;

/** Common editorial atoms, not a shared scope validator: each input has its own immutable schema. */
public sealed interface WeeklyReviewAiEditorialInput permits WeeklyReviewAiInput, SellerWeeklyReviewAiInput {
    int contractVersion();
    String promptVersion();
    int contentSchemaVersion();
    String reportState();
    WeeklyReviewAiInput.SummarySource summary();
    List<WeeklyReviewAiInput.FactorSource> factors();
    List<WeeklyReviewAiInput.ActionSource> actions();
    List<WeeklyReviewAiInput.EvidenceSource> evidence();
}
