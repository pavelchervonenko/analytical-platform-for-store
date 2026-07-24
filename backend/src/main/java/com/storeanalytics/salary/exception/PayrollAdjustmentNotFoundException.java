package com.storeanalytics.salary.exception;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;
import java.util.UUID;

public class PayrollAdjustmentNotFoundException extends BusinessException {

    public PayrollAdjustmentNotFoundException(UUID adjustmentId) {
        super(
                BusinessErrorCode.PAYROLL_ADJUSTMENT_NOT_FOUND,
                "Payroll adjustment was not found: " + adjustmentId
        );
    }
}
