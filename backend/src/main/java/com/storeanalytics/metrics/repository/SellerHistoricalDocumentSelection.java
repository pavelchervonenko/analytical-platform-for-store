package com.storeanalytics.metrics.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One document's seller eligibility before item-level aggregation. */
public record SellerHistoricalDocumentSelection(
        UUID documentId,
        UUID employeeId,
        LocalDate businessDate,
        Instant membershipAt,
        Bucket bucket,
        Reason reason
) {
    public enum Bucket {
        SELLER_ELIGIBLE,
        KNOWN_OUTSIDE_SELLER_COHORT,
        UNKNOWN_MEMBERSHIP_HISTORY,
        UNKNOWN_EMPLOYEE_ATTRIBUTION,
        ORPHAN_RETURN
    }

    public enum Reason {
        NONE,
        EXPLICITLY_INELIGIBLE_EMPLOYEE,
        UNATTRIBUTED_SALE,
        UNATTRIBUTED_RETURN,
        UNRESOLVED_RETURN_EMPLOYEE,
        LINKED_TO_UNATTRIBUTED_ORIGINAL,
        MISSING_OR_INVALID_ORIGINAL,
        HISTORY_UNKNOWN
    }
}
