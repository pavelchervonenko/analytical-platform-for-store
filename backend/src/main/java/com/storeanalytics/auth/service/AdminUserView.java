package com.storeanalytics.auth.service;

import com.storeanalytics.auth.model.UserRole;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdminUserView(
        UUID id,
        String email,
        String displayName,
        UserRole role,
        boolean active,
        boolean passwordChangeRequired,
        boolean allStores,
        List<UUID> storeIds,
        Instant lastLoginAt,
        long version
) {
}
