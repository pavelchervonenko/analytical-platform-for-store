package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

/** Complete-interval coverage for each of the two weeks and each seller financial source. */
record SellerWeeklySourceCoverage(
        Window sales,
        Window returns,
        Window orders
) {

    SellerWeeklySourceCoverage {
        requireNonNull(sales, "sales");
        requireNonNull(returns, "returns");
        requireNonNull(orders, "orders");
    }

    static SellerWeeklySourceCoverage complete() {
        Window full = new Window(true, true);
        return new SellerWeeklySourceCoverage(full, full, full);
    }

    boolean completeBothWeeks() {
        return sales.current() && sales.previous()
                && returns.current() && returns.previous()
                && orders.current() && orders.previous();
    }

    record Window(boolean current, boolean previous) {
    }
}
