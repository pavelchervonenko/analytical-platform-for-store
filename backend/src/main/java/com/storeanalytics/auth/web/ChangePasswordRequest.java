package com.storeanalytics.auth.web;

import com.storeanalytics.auth.service.PasswordPolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
        @NotBlank @Size(max = PasswordPolicy.MAXIMUM_LENGTH) String currentPassword,
        @NotBlank @Size(
                min = PasswordPolicy.MINIMUM_LENGTH,
                max = PasswordPolicy.MAXIMUM_LENGTH
        ) String newPassword
) {
}
