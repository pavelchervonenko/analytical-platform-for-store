package com.storeanalytics.sync.exception;

import java.time.Duration;

/** Local quota exhaustion is not an upstream HTTP429 observation. */
public class HistoricalSalesReadBudgetException extends RuntimeException {
    private final Duration retryAfter;
    private final boolean daily;

    public HistoricalSalesReadBudgetException(Duration retryAfter, boolean daily) {
        super("Historical SALE read budget exhausted");
        this.retryAfter = retryAfter;
        this.daily = daily;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    public boolean daily() {
        return daily;
    }
}
