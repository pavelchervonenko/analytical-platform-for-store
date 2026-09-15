package com.storeanalytics.interpretation.review.ai;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;

public final class WeeklyReviewAiSnapshotNotEligibleException
        extends BusinessException {

    public WeeklyReviewAiSnapshotNotEligibleException() {
        super(
                BusinessErrorCode.WEEKLY_REVIEW_AI_OPERATIONS_CONFLICT,
                "Weekly review snapshot is not eligible for AI enrichment"
        );
    }
}
