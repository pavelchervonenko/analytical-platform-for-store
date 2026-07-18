package com.storeanalytics.product.model;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.auth.model.AppUser;
import java.time.Instant;

public record CategoryAssignmentDetails(
        ProductConditionType conditionType,
        CategoryAssignmentSource assignmentSource,
        String ruleVersion,
        Instant validFrom,
        Instant validTo,
        AppUser assignedBy,
        String changeReason
) {

    public CategoryAssignmentDetails {
        conditionType = requireNonNull(conditionType, "conditionType");
        assignmentSource = requireNonNull(assignmentSource, "assignmentSource");
        validFrom = requireNonNull(validFrom, "validFrom");
        require(validTo == null || validTo.isAfter(validFrom),
                "validTo must be after validFrom");
    }
}
