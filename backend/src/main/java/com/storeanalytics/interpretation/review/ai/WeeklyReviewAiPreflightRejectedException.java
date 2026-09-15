package com.storeanalytics.interpretation.review.ai;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;

public final class WeeklyReviewAiPreflightRejectedException
        extends BusinessException {

    public WeeklyReviewAiPreflightRejectedException() {
        super(
                BusinessErrorCode.WEEKLY_REVIEW_AI_OPERATIONS_CONFLICT,
                "Weekly review AI preflight rejected the exact snapshot"
        );
    }
}
