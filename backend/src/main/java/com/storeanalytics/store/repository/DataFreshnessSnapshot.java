package com.storeanalytics.store.repository;

import java.time.Instant;

public record DataFreshnessSnapshot(
        Instant oldestSalesThrough,
        Instant oldestReturnsThrough,
        Instant oldestOrdersThrough,
        long storesWithoutSales,
        long storesWithoutReturns,
        long storesWithoutOrders
) {

    public DataFreshnessSnapshot(
            Instant oldestSalesThrough,
            Instant oldestReturnsThrough,
            long storesWithoutSales,
            long storesWithoutReturns
    ) {
        this(oldestSalesThrough, oldestReturnsThrough, oldestReturnsThrough,
                storesWithoutSales, storesWithoutReturns, storesWithoutReturns);
    }
}
