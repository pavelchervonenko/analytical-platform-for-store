package com.storeanalytics.salary.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record PayrollSchemeView(
        UUID id,
        String code,
        LocalDate effectiveFrom,
        BigDecimal achievedPercentage,
        BigDecimal missedPercentage,
        BigDecimal achievedTier1Rate,
        BigDecimal missedTier1Rate,
        BigDecimal achievedTier2Rate,
        BigDecimal missedTier2Rate,
        BigDecimal advanceAmount
) {
}
