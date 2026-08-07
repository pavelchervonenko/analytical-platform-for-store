package com.storeanalytics.interpretation.exception;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;
import java.util.UUID;

public final class WeeklyInterpretationNotFoundException extends BusinessException {

    private WeeklyInterpretationNotFoundException(String message) {
        super(BusinessErrorCode.WEEKLY_INTERPRETATION_NOT_FOUND, message);
    }

    public static WeeklyInterpretationNotFoundException latest() {
        return new WeeklyInterpretationNotFoundException(
                "latest weekly interpretation does not exist"
        );
    }

    public static WeeklyInterpretationNotFoundException byId(UUID id) {
        return new WeeklyInterpretationNotFoundException(
                "weekly interpretation does not exist: " + id
        );
    }
}
