package com.storeanalytics.auth.web;

import java.time.Instant;

public record ActiveSessionResponse(
        String sessionReference,
        Instant lastSeenAt,
        boolean current
) {
}
