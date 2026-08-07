package com.storeanalytics.notification.operations;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ManualTelegramResendRequest(
        @NotBlank @Size(min = 10, max = 500) String reason,
        @NotNull @AssertTrue Boolean acknowledgeDuplicateRisk
) {
}
