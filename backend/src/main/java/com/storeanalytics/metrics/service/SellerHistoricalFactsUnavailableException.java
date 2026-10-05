package com.storeanalytics.metrics.service;

/** Safe preparation reason: UNKNOWN is never silently removed from a historical financial total. */
public class SellerHistoricalFactsUnavailableException extends RuntimeException {
    public SellerHistoricalFactsUnavailableException(String reason) {
        super(reason);
    }
}
