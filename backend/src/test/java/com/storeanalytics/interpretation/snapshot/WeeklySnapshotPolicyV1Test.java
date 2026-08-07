package com.storeanalytics.interpretation.snapshot;

import static com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Sufficiency.INSUFFICIENT;
import static com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Sufficiency.LIMITED;
import static com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Sufficiency.SUFFICIENT;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class WeeklySnapshotPolicyV1Test {

    private final WeeklySnapshotPolicyV1 policy = new WeeklySnapshotPolicyV1();

    @Test
    void appliesApprovedWorkloadBoundaries() {
        assertThat(policy.workload(0, BigDecimal.ZERO)).isEqualTo(INSUFFICIENT);
        assertThat(policy.workload(1, new BigDecimal("20.00"))).isEqualTo(LIMITED);
        assertThat(policy.workload(2, new BigDecimal("11.99"))).isEqualTo(LIMITED);
        assertThat(policy.workload(2, new BigDecimal("12.00"))).isEqualTo(SUFFICIENT);
    }

    @Test
    void appliesApprovedSalesAndAttachBoundaries() {
        assertThat(policy.salesStructure(new BigDecimal("2.999"))).isEqualTo(INSUFFICIENT);
        assertThat(policy.salesStructure(new BigDecimal("3.000"))).isEqualTo(LIMITED);
        assertThat(policy.salesStructure(new BigDecimal("6.000"))).isEqualTo(SUFFICIENT);

        assertThat(policy.attach(new BigDecimal("2.999"))).isEqualTo(INSUFFICIENT);
        assertThat(policy.attach(new BigDecimal("3.000"))).isEqualTo(LIMITED);
        assertThat(policy.attach(new BigDecimal("5.000"))).isEqualTo(SUFFICIENT);
    }

    @Test
    void requiresThreeEmployeesAndFivePercentForUnambiguousTeamComparison() {
        assertThat(policy.teamBenchmarkAllowed(2)).isFalse();
        assertThat(policy.teamBenchmarkAllowed(3)).isTrue();
        assertThat(policy.clearLeader(new BigDecimal("104.99"), new BigDecimal("100")))
                .isFalse();
        assertThat(policy.clearLeader(new BigDecimal("105.00"), new BigDecimal("100")))
                .isTrue();
    }
}
