package com.storeanalytics.auth.service;

import com.storeanalytics.auth.model.UserFeature;
import com.storeanalytics.auth.model.UserRole;
import java.util.Set;
import java.util.UUID;

public record UpdateUserCommand(
        String displayName,
        UserRole role,
        boolean active,
        Set<UUID> storeIds,
        Set<UserFeature> features,
        long version
) {
}
