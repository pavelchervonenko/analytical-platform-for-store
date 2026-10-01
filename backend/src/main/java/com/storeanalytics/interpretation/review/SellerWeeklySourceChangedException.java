package com.storeanalytics.interpretation.review;

/** Signals that a complete fresh facts read is required before retrying v3 generation. */
class SellerWeeklySourceChangedException extends RuntimeException {

    SellerWeeklySourceChangedException() {
        super("Seller weekly source changed before snapshot persistence");
    }
}
