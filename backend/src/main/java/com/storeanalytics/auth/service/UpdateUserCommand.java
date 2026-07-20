package com.storeanalytics.auth.service;

import com.storeanalytics.auth.model.UserRole;

public record UpdateUserCommand(
        String displayName,
        UserRole role,
        boolean active
) {
}
