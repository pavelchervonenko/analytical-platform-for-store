package com.storeanalytics.metrics.repository;

import java.math.BigDecimal;

public interface CategoryMetricValues {

    BigDecimal netRevenue();

    BigDecimal netQuantity();

    BigDecimal costAmount();

    long includedItemCount();

    long missingCostItemCount();

    long unexpectedZeroCostItemCount();
}
