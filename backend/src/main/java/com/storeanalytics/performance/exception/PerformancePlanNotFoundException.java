package com.storeanalytics.performance.exception;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;
import java.time.LocalDate;
import java.util.UUID;

public class PerformancePlanNotFoundException extends BusinessException {

    public PerformancePlanNotFoundException(UUID storeId, LocalDate planMonth) {
        super(
                BusinessErrorCode.PERFORMANCE_PLAN_NOT_FOUND,
                "Performance plan was not found for store " + storeId
                        + " and month " + planMonth
        );
    }
}
