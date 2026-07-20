package com.storeanalytics.auth.web;

import com.storeanalytics.auth.model.UserRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateUserRequest(
        @NotBlank @Size(max = 200) String displayName,
        @NotNull UserRole role,
        boolean active
) {
}
