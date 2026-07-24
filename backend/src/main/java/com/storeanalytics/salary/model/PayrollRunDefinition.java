package com.storeanalytics.salary.model;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;
import static com.storeanalytics.common.validation.ModelValidation.require;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.store.model.Store;
import java.time.LocalDate;

public record PayrollRunDefinition(
        Store store,
        LocalDate periodMonth,
        int revision,
        PayrollRun supersedes,
        String revisionReason,
        PayrollScheme scheme,
        PayrollPlanResult planResult,
        PayrollRunQuality quality,
        PayrollSourceFingerprint sourceFingerprint,
        AppUser createdBy
) {

    public PayrollRunDefinition {
        store = requireNonNull(store, "store");
        periodMonth = requireNonNull(periodMonth, "periodMonth");
        require(periodMonth.getDayOfMonth() == 1,
                "periodMonth must be the first day of a month");
        require(revision > 0, "revision must be positive");
        require((revision == 1 && supersedes == null)
                        || (revision > 1 && supersedes != null),
                "revision and supersedes must agree");
        if (revision > 1) {
            revisionReason = requireText(revisionReason, "revisionReason");
        }
        scheme = requireNonNull(scheme, "scheme");
        planResult = requireNonNull(planResult, "planResult");
        quality = requireNonNull(quality, "quality");
        sourceFingerprint = requireNonNull(sourceFingerprint, "sourceFingerprint");
    }
}
