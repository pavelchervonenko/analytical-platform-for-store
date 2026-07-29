package com.storeanalytics.report.service;

import com.storeanalytics.report.model.ReportBackfillJobPhase;
import java.util.UUID;

record ReportBackfillJobClaim(
        UUID jobId,
        ReportBackfillJobPhase phase,
        int attemptCount
) {
}
