package com.storeanalytics.interpretation.review.ai;

import com.storeanalytics.interpretation.review.ClosedSellerWeek;
import com.storeanalytics.interpretation.review.PersistedWeeklyReview;
import com.storeanalytics.interpretation.review.SellerWeeklyReviewService;
import com.storeanalytics.interpretation.review.SellerWeeklyHistoricalReviewService;
import com.storeanalytics.interpretation.review.SellerWeeklyReviewView;
import com.storeanalytics.interpretation.review.WeeklyReviewV3Response;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.ReportState;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

/** Optional AI may only consume the exact currently eligible seller snapshot. */
@Component
public final class SellerWeeklyReviewAiFreshnessGuard {
    private final SellerWeeklyReviewService reviews;
    private final SellerWeeklyHistoricalReviewService historical;

    public SellerWeeklyReviewAiFreshnessGuard(SellerWeeklyReviewService reviews) {
        this(reviews, null);
    }

    @Autowired
    public SellerWeeklyReviewAiFreshnessGuard(SellerWeeklyReviewService reviews,
            SellerWeeklyHistoricalReviewService historical) {
        this.reviews = reviews;
        this.historical = historical;
    }

    public boolean isCurrent(PersistedWeeklyReview snapshot) {
        if (snapshot.response().contractVersion() != 3) {
            return false;
        }
        if (isHistorical(snapshot) && historical == null) {
            return false;
        }
        var current = isHistorical(snapshot)
                ? historical.period(snapshot.storeId(), snapshot.response().period().current().start())
                : reviews.current(snapshot.storeId());
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
        if (isHistorical(snapshot)) {
            if (historical == null || !new ClosedSellerWeek(period.current().start(),
                    java.time.ZoneId.of(period.timezone())).isClosedAt(now)) {
                return false;
            }
            historical.refresh(snapshot.storeId(), period.current().start(), period.timezone());
            return isCurrent(snapshot);
        }
        var latest = ClosedSellerWeek.latest(now, period.timezone());
        if (!latest.start().equals(period.current().start()) || !latest.end().equals(period.current().end())
                || !latest.previous().start().equals(period.previous().start())
                || !latest.previous().end().equals(period.previous().end())) {
            return false;
        }
        reviews.generate(snapshot.storeId());
        return isCurrent(snapshot);
    }

    private boolean isHistorical(PersistedWeeklyReview snapshot) {
        return snapshot.response() instanceof WeeklyReviewV3Response report && report.membership() != null
                && "HISTORICAL_DOCUMENT_MEMBERSHIP_V1".equals(report.membership().basis());
    }
}
