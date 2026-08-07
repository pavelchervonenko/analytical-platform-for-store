package com.storeanalytics.performance.repository;

import com.storeanalytics.product.model.AttachDenominatorCode;
import java.math.BigDecimal;
import java.util.UUID;

public record EmployeeAttachRateAggregate(
        UUID employeeId,
        String metricCode,
        String numeratorCategoryCode,
        AttachDenominatorCode denominatorCode,
        BigDecimal numeratorReceiptCount,
        BigDecimal denominatorReceiptCount
) {
}
