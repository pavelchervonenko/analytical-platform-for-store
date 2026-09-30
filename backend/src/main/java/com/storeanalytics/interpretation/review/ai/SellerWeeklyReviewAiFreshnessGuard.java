package com.storeanalytics.interpretation.review.ai;

import com.storeanalytics.interpretation.review.PersistedWeeklyReview;
import com.storeanalytics.interpretation.review.SellerWeeklyReviewService;
import com.storeanalytics.interpretation.review.SellerWeeklyReviewView;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.ReportState;
import org.springframework.stereotype.Component;

/** Optional AI may only consume the exact currently eligible seller snapshot. */
@Component
public final class SellerWeeklyReviewAiFreshnessGuard {
    private final SellerWeeklyReviewService reviews;

    public SellerWeeklyReviewAiFreshnessGuard(SellerWeeklyReviewService reviews) {
        this.reviews = reviews;
    }

    public boolean isCurrent(PersistedWeeklyReview snapshot) {
        if (snapshot.response().contractVersion() != 3) {
            return false;
        }
        var current = reviews.current(snapshot.storeId());
        return current.freshness() == SellerWeeklyReviewView.Freshness.CURRENT
                && snapshot.id().toString().equals(current.report().provenance().snapshotPublicId())
                && (current.report().reportState() == ReportState.READY
                    || current.report().reportState() == ReportState.PARTIAL);
    }
}
