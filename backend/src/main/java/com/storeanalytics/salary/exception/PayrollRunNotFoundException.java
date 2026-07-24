package com.storeanalytics.salary.exception;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;
import java.util.UUID;

public class PayrollRunNotFoundException extends BusinessException {

    public PayrollRunNotFoundException(UUID runId) {
        super(BusinessErrorCode.PAYROLL_NOT_FOUND, "Payroll run was not found: " + runId);
    }
}
