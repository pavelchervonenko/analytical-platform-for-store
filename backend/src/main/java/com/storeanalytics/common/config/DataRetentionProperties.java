package com.storeanalytics.common.config;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.maintenance.retention")
public record DataRetentionProperties(
        boolean deletionEnabled,
        int deleteBatchSize,
        int rollupBatchSize,
        Duration normalizedRawRetention,
        Duration problemRawRetention,
        Duration successfulSyncRunRetention,
        Duration unsuccessfulSyncRunRetention,
        Duration successfulSyncJobRetention,
        Duration unsuccessfulSyncJobRetention,
        Duration closedQualityIssueRetention,
        Period detailedInventoryRetention,
        Period dailyInventoryRetention,
        ZoneId zone,
        Audit audit,
        DeletionAuthorization deletionAuthorization
) {

    private static final LocalDate COMPARISON_DATE = LocalDate.of(2000, 1, 1);

    public DataRetentionProperties {
        requirePositive(deleteBatchSize, "deleteBatchSize");
        requirePositive(rollupBatchSize, "rollupBatchSize");
        requirePositive(normalizedRawRetention, "normalizedRawRetention");
        requirePositive(problemRawRetention, "problemRawRetention");
        requirePositive(successfulSyncRunRetention, "successfulSyncRunRetention");
        requirePositive(unsuccessfulSyncRunRetention, "unsuccessfulSyncRunRetention");
        requirePositive(successfulSyncJobRetention, "successfulSyncJobRetention");
        requirePositive(unsuccessfulSyncJobRetention, "unsuccessfulSyncJobRetention");
        requirePositive(closedQualityIssueRetention, "closedQualityIssueRetention");
        requirePositive(detailedInventoryRetention, "detailedInventoryRetention");
        requirePositive(dailyInventoryRetention, "dailyInventoryRetention");
        if (!COMPARISON_DATE.plus(dailyInventoryRetention)
                .isAfter(COMPARISON_DATE.plus(detailedInventoryRetention))) {
            throw new IllegalArgumentException(
                    "dailyInventoryRetention must exceed detailedInventoryRetention"
            );
        }
        if (problemRawRetention.compareTo(normalizedRawRetention) < 0) {
            throw new IllegalArgumentException(
                    "problemRawRetention must not be shorter than normalizedRawRetention"
            );
        }
        if (unsuccessfulSyncRunRetention.compareTo(successfulSyncRunRetention) < 0
                || unsuccessfulSyncJobRetention.compareTo(successfulSyncJobRetention) < 0) {
            throw new IllegalArgumentException(
                    "unsuccessful synchronization retention must not be shorter"
            );
        }
        if (zone == null || audit == null || deletionAuthorization == null) {
            throw new IllegalArgumentException(
                    "retention zone, audit policy and deletion authorization are required"
            );
        }
        if (deletionEnabled && !deletionAuthorization.isConfigured()) {
            throw new IllegalArgumentException(
                    "retention deletion requires approved policy, backup and restore evidence"
            );
        }
    }

    private static void requirePositive(int value, String field) {
        if (value < 1 || value > 100_000) {
            throw new IllegalArgumentException(field + " must be between 1 and 100000");
        }
    }

    private static void requirePositive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private static void requirePositive(Period value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }


    public record DeletionAuthorization(
            String policyApprovalReference,
            String backupCheckpointReference,
            Instant restoreTestedAt,
            Duration maximumRestoreTestAge
    ) {

        static final String UNAPPROVED = "UNAPPROVED";
        static final String UNVERIFIED = "UNVERIFIED";

        public DeletionAuthorization {
            policyApprovalReference = requireReference(
                    policyApprovalReference,
                    "policyApprovalReference"
            );
            backupCheckpointReference = requireReference(
                    backupCheckpointReference,
                    "backupCheckpointReference"
            );
            if (restoreTestedAt == null) {
                throw new IllegalArgumentException("restoreTestedAt is required");
            }
            requirePositive(maximumRestoreTestAge, "maximumRestoreTestAge");
            if (maximumRestoreTestAge.compareTo(Duration.ofDays(365)) > 0) {
                throw new IllegalArgumentException(
                        "maximumRestoreTestAge must not exceed 365 days"
                );
            }
        }

        public boolean isConfigured() {
            return !UNAPPROVED.equals(policyApprovalReference)
                    && !UNVERIFIED.equals(backupCheckpointReference);
        }

        private static String requireReference(String value, String field) {
            if (value == null || value.isBlank() || value.length() > 256) {
                throw new IllegalArgumentException(
                        field + " must contain between 1 and 256 characters"
                );
            }
            return value;
        }
    }

    public record Audit(
            Period securityRetention,
            Period businessRetention,
            Period operationalRetention
    ) {

        public Audit {
            requirePositive(securityRetention, "audit.securityRetention");
            requirePositive(businessRetention, "audit.businessRetention");
            requirePositive(operationalRetention, "audit.operationalRetention");
            LocalDate security = COMPARISON_DATE.plus(securityRetention);
            LocalDate business = COMPARISON_DATE.plus(businessRetention);
            LocalDate operational = COMPARISON_DATE.plus(operationalRetention);
            if (security.isBefore(business) || business.isBefore(operational)) {
                throw new IllegalArgumentException(
                        "audit retention must satisfy security >= business >= operational"
                );
            }
        }
    }
}
