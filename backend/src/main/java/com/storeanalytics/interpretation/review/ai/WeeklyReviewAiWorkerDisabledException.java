package com.storeanalytics.interpretation.review.ai;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;

public final class WeeklyReviewAiWorkerDisabledException
        extends BusinessException {

    public WeeklyReviewAiWorkerDisabledException() {
        super(
                BusinessErrorCode.WEEKLY_REVIEW_AI_OPERATIONS_CONFLICT,
                "Weekly review AI worker is disabled"
        );
    }
}
