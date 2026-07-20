package com.storeanalytics.sync.web;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record CreateBackfillSyncJobRequest(
        @NotNull LocalDate periodStart,
        @NotNull LocalDate periodEndInclusive
) {
}
