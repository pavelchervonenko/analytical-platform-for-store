package com.storeanalytics.interpretation.review.ai;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;

public final class WeeklyReviewAiJobNotFoundException extends BusinessException {

    public WeeklyReviewAiJobNotFoundException() {
        super(
                BusinessErrorCode.WEEKLY_REVIEW_AI_JOB_NOT_FOUND,
                "Weekly review AI job does not exist"
        );
    }
}
