package com.storeanalytics.report.model;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.store.model.Store;

public record ReportBackfillJobDefinition(
        Store store,
        AppUser requestedBy,
        String idempotencyKey,
        int year,
        int maxAttempts
) {
}
