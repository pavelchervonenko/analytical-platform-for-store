package com.storeanalytics.report.service;

import java.util.UUID;

public record ReportActorView(
        UUID id,
        String displayName
) {
}
