package com.storeanalytics.salary.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PayrollDayRevisionChange(
        LocalDate workDate,
        BigDecimal previousFundAmount,
        BigDecimal currentFundAmount,
        BigDecimal fundChange,
        int previousShiftEmployeeCount,
        int currentShiftEmployeeCount,
        List<String> reasons
) {
}
