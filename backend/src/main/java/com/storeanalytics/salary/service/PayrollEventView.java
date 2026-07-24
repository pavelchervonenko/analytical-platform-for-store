package com.storeanalytics.salary.service;

import com.storeanalytics.salary.model.PayrollEventType;
import java.time.Instant;
import java.util.UUID;

public record PayrollEventView(
        UUID id,
        PayrollEventType type,
        UUID actorId,
        String details,
        Instant createdAt
) {
}
