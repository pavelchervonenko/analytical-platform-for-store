package com.storeanalytics.auth.service;

import java.time.Instant;

public record ActiveSessionView(
        String sessionReference,
        Instant lastSeenAt,
        boolean current
) {
}
