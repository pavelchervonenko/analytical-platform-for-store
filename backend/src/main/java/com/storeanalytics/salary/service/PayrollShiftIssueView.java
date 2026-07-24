package com.storeanalytics.salary.service;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PayrollShiftIssueView(
        LocalDate workDate,
        BigDecimal fundAmount
) {
}
