package com.storeanalytics.interpretation.review.ai;

import com.storeanalytics.interpretation.review.ClosedSellerWeek;
import com.storeanalytics.interpretation.review.PersistedWeeklyReview;
import com.storeanalytics.interpretation.review.SellerWeeklyReviewService;
import com.storeanalytics.interpretation.review.SellerWeeklyReviewView;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.ReportState;
import java.time.Instant;
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

    /** Free re-evaluation may reuse the exact immutable snapshot, never substitute a new revision. */
    public boolean refreshIfSameSnapshot(PersistedWeeklyReview snapshot, Instant now) {
        if (snapshot.response().contractVersion() != 3) {
            return false;
        }
        var period = snapshot.response().period();
        var latest = ClosedSellerWeek.latest(now, period.timezone());
        if (!latest.start().equals(period.current().start()) || !latest.end().equals(period.current().end())
                || !latest.previous().start().equals(period.previous().start())
                || !latest.previous().end().equals(period.previous().end())) {
            return false;
        }
        reviews.generate(snapshot.storeId());
        return isCurrent(snapshot);
    }
}
