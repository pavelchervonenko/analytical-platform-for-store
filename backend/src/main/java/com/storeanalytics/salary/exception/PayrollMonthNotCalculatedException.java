package com.storeanalytics.salary.exception;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;
import java.time.YearMonth;
import java.util.UUID;

public class PayrollMonthNotCalculatedException extends BusinessException {

    public PayrollMonthNotCalculatedException(UUID storeId, YearMonth month) {
        super(
                BusinessErrorCode.PAYROLL_NOT_FOUND,
                "Payroll was not calculated for store " + storeId + " and month " + month
        );
    }
}
