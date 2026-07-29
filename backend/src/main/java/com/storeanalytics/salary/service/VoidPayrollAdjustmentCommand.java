package com.storeanalytics.salary.service;

import java.util.UUID;

public record VoidPayrollAdjustmentCommand(
        UUID storeId,
        UUID runId,
        UUID adjustmentId,
        String reason,
        long runVersion,
        long adjustmentVersion,
        UUID actorId
) {
}
