package com.storeanalytics.interpretation.review;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse.PeriodContext;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Provenance;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.ReportState;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.VersionSet;

/** Minimal common header; persisted payloads retain distinct concrete versioned types. */
public sealed interface WeeklyReviewContract permits WeeklyReviewResponse, WeeklyReviewV3Response {

    int contractVersion();

    VersionSet versions();

    PeriodContext period();

    Provenance provenance();

    ReportState reportState();
}
