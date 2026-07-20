package com.storeanalytics.product.service;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record ProductCategoryImportCommand(
        String connectionKey,
        Instant validFrom,
        String ruleVersion,
        String changeReason,
        List<ProductCategoryImportEntry> assignments
) {

    private static final int MAX_ASSIGNMENTS = 10_000;

    public ProductCategoryImportCommand {
        connectionKey = requireText(connectionKey, "connectionKey");
        validFrom = requireNonNull(validFrom, "validFrom");
        ruleVersion = requireText(ruleVersion, "ruleVersion");
        assignments = List.copyOf(requireNonNull(assignments, "assignments"));
        require(!assignments.isEmpty(), "assignments must not be empty");
        require(assignments.size() <= MAX_ASSIGNMENTS,
                "assignments must contain no more than " + MAX_ASSIGNMENTS + " entries");

        Set<String> externalIds = new HashSet<>();
        for (ProductCategoryImportEntry assignment : assignments) {
            requireNonNull(assignment, "assignment");
            require(externalIds.add(assignment.externalProductId()),
                    "duplicate externalProductId " + assignment.externalProductId());
        }
    }
}
