package com.storeanalytics.auth.web;

import jakarta.validation.constraints.NotNull;
import java.util.Set;
import java.util.UUID;

public record StoreAccessRequest(@NotNull Set<UUID> storeIds) {
}
