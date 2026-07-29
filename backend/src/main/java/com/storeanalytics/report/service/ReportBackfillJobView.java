package com.storeanalytics.report.service;

import com.storeanalytics.report.model.ReportBackfillJob;
import com.storeanalytics.report.model.ReportBackfillJobPhase;
import com.storeanalytics.report.model.ReportBackfillJobStatus;
import java.time.Instant;
import java.util.UUID;

public record ReportBackfillJobView(
        UUID id,
        UUID storeId,
        UUID requestedById,
        int year,
        ReportBackfillJobStatus status,
        ReportBackfillJobPhase phase,
        int cursorMonth,
        int paidMonthCount,
        int monthlyCreatedCount,
        int monthlyExistingCount,
        UUID annualReportId,
        int attemptCount,
        int maxAttempts,
        int completedSteps,
        int totalRetries,
        boolean cancelRequested,
        Instant nextAttemptAt,
        Instant leaseUntil,
        String errorSummary,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt,
        Instant updatedAt
) {

    static ReportBackfillJobView from(ReportBackfillJob job) {
        return new ReportBackfillJobView(
                job.getId(),
                job.getStore().getId(),
                job.getRequestedBy() == null ? null : job.getRequestedBy().getId(),
                job.getYear(),
                job.getStatus(),
                job.getPhase(),
                job.getCursorMonth(),
                job.getPaidMonthCount(),
                job.getMonthlyCreatedCount(),
                job.getMonthlyExistingCount(),
                job.getAnnualReport() == null
                        ? null : job.getAnnualReport().getId(),
                job.getAttemptCount(),
                job.getMaxAttempts(),
                job.getCompletedSteps(),
                job.getTotalRetries(),
                job.isCancelRequested(),
                job.getNextAttemptAt(),
                job.getLeaseUntil(),
                job.getErrorSummary(),
                job.getStartedAt(),
                job.getFinishedAt(),
                job.getCreatedAt(),
                job.getUpdatedAt()
        );
    }
}
