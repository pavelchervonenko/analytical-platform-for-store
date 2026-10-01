package com.storeanalytics.interpretation.review;

/** Whether a seller-week source is safe to present as reconciled. */
enum SellerWeeklySourceStability {
    STABLE,
    IN_PROGRESS,
    NEEDS_RECONCILIATION
}
