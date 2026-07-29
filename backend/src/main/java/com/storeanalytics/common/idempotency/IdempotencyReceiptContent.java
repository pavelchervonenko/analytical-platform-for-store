package com.storeanalytics.common.idempotency;

record IdempotencyReceiptContent(
        String action,
        String resourceIdentity,
        String requestHash,
        String responseType,
        String responseBody
) {
}
