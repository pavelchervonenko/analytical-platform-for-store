package com.storeanalytics.sync.web;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

record SalesSyncRequest(
        @NotNull Instant periodStart,
        @NotNull Instant periodEnd
) {
}
