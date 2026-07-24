package com.storeanalytics.salary.service;

import com.storeanalytics.salary.model.PayrollDailyPoolAmounts;
import com.storeanalytics.salary.model.PayrollDailyPoolInput;
import java.util.List;

record PayrollComputedDay(
        PayrollDailyPoolInput input,
        PayrollDailyPoolAmounts amounts,
        List<PayrollComputedShift> shifts,
        List<PayrollComputedAllocation> allocations
) {
}
