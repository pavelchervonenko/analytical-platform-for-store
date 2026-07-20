package com.storeanalytics.auth.service;

import com.storeanalytics.auth.model.UserRole;
import java.util.Set;
import java.util.UUID;

public record CreateUserCommand(
        String email,
        String temporaryPassword,
        String displayName,
        UserRole role,
        Set<UUID> storeIds
) {
}
