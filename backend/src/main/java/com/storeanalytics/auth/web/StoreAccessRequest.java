package com.storeanalytics.auth.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.Set;
import java.util.UUID;

public record StoreAccessRequest(
        @NotNull Set<@NotNull UUID> storeIds,
        @NotNull @PositiveOrZero Long version
) {
}
