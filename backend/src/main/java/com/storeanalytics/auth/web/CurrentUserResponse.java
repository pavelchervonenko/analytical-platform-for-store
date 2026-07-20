package com.storeanalytics.auth.web;

import com.storeanalytics.auth.model.UserRole;
import java.util.List;
import java.util.UUID;

public record CurrentUserResponse(
        UUID id,
        String email,
        String displayName,
        UserRole role,
        boolean passwordChangeRequired,
        boolean allStores,
        List<UUID> storeIds
) {
}
