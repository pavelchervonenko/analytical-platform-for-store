package com.storeanalytics.performance.exception;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;
import java.time.LocalDate;

public class RatingPeriodNotClosedException extends BusinessException {

    public RatingPeriodNotClosedException(LocalDate periodEnd, LocalDate currentDate) {
        super(
                BusinessErrorCode.RATING_PERIOD_NOT_CLOSED,
                "Rating period ending " + periodEnd
                        + " cannot be finalized before " + currentDate.plusDays(1)
        );
    }
}
