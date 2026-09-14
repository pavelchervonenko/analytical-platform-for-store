package com.storeanalytics.auth.web;

import com.storeanalytics.auth.model.UserFeature;
import com.storeanalytics.auth.model.UserRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.Set;
import java.util.UUID;

public record UpdateUserRequest(
        @NotBlank @Size(max = 200) String displayName,
        @NotNull UserRole role,
        @NotNull Boolean active,
        @NotNull Set<@NotNull UUID> storeIds,
        @NotNull Set<@NotNull UserFeature> features,
        @NotNull @PositiveOrZero Long version
) {
}
