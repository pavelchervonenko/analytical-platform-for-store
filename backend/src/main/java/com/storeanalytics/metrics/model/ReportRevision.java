package com.storeanalytics.metrics.model;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.salary.model.PayrollRun;

public record ReportRevision(
        int number,
        ReportSnapshot supersedes,
        PayrollRun payrollRun,
        String reason,
        int schemaVersion
) {

    public ReportRevision {
        require(number > 0, "revision number must be positive");
        require(schemaVersion > 0, "schemaVersion must be positive");
        if (number == 1) {
            require(supersedes == null, "first revision cannot supersede another report");
        } else {
            requireNonNull(supersedes, "supersedes");
            require(reason != null && !reason.isBlank(),
                    "a later revision requires a reason");
        }
    }
}
