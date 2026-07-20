package com.storeanalytics.auth.web;

import com.storeanalytics.auth.model.UserRole;
import com.storeanalytics.auth.service.PasswordPolicy;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Set;
import java.util.UUID;

public record CreateUserRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(
                min = PasswordPolicy.MINIMUM_LENGTH,
                max = PasswordPolicy.MAXIMUM_LENGTH
        ) String temporaryPassword,
        @NotBlank @Size(max = 200) String displayName,
        @NotNull UserRole role,
        @NotNull Set<UUID> storeIds
) {
}
