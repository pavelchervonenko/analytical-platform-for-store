package com.storeanalytics.common.idempotency;

public record IdempotencyRequest(
        String action,
        String resource,
        Object body
) {
}
