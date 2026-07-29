package com.storeanalytics.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class DataRetentionPropertiesTest {

    @Test
    void permitsDryRunWithExplicitlyUnapprovedEvidencePlaceholders() {
        assertThat(properties(false, authorization("UNAPPROVED", "UNVERIFIED"))
                .deletionEnabled()).isFalse();
    }

    @Test
    void deletionRequiresAttributablePolicyAndBackupEvidence() {
        assertThatThrownBy(() -> properties(
                true,
                authorization("UNAPPROVED", "backup-2026-07")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("approved policy, backup and restore evidence");

        assertThat(properties(
                true,
                authorization("policy-2026-07", "backup-2026-07")
        ).deletionEnabled()).isTrue();
    }

    private DataRetentionProperties.DeletionAuthorization authorization(
            String policy,
            String backup
    ) {
        return new DataRetentionProperties.DeletionAuthorization(
                policy,
                backup,
                Instant.parse("2026-07-01T00:00:00Z"),
                Duration.ofDays(90)
        );
    }

    private DataRetentionProperties properties(
            boolean deletionEnabled,
            DataRetentionProperties.DeletionAuthorization authorization
    ) {
        return new DataRetentionProperties(
                deletionEnabled,
                10_000,
                1_000,
                Duration.ofDays(180),
                Duration.ofDays(365),
                Duration.ofDays(90),
                Duration.ofDays(365),
                Duration.ofDays(90),
                Duration.ofDays(180),
                Duration.ofDays(365),
                Period.ofMonths(13),
                Period.ofYears(3),
                ZoneId.of("Europe/Kaliningrad"),
                new DataRetentionProperties.Audit(
                        Period.ofYears(5),
                        Period.ofYears(3),
                        Period.ofYears(1)
                ),
                authorization
        );
    }
}
