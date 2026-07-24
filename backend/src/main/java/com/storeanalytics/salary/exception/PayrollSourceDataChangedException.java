package com.storeanalytics.salary.exception;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;
import com.storeanalytics.salary.service.PayrollStaleReason;
import java.util.List;

public class PayrollSourceDataChangedException extends BusinessException {

    private final List<PayrollStaleReason> reasons;

    public PayrollSourceDataChangedException(List<PayrollStaleReason> reasons) {
        super(
                BusinessErrorCode.PAYROLL_SOURCE_DATA_CHANGED,
                "Payroll source data changed; recalculate before changing payroll status"
        );
        this.reasons = List.copyOf(reasons);
    }

    public List<PayrollStaleReason> getReasons() {
        return reasons;
    }
}
