package com.storeanalytics.metrics.warranty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record WarrantyDecisionRequest(
        @NotNull Action action,
        @NotBlank @Size(max = 1000) String reason,
        UUID originalWarrantyItemId,
        @NotNull @Size(max = 100) List<@Valid Allocation> allocations
) {
    public enum Action { ALLOCATE, EXCLUDE, DEFER }

    public record Allocation(
            @NotNull UUID deviceItemId,
            @NotNull @DecimalMin("0.001") @Digits(integer = 16, fraction = 3) BigDecimal quantity,
            @NotBlank @Size(max = 64) String fingerprint
    ) {
    }
}
