package com.storeanalytics.interpretation.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.QualityStatus;
import com.storeanalytics.metrics.service.AttachRateDataQuality;
import com.storeanalytics.metrics.service.StoreKpiDataQuality;
import com.storeanalytics.store.service.StoreDataFreshnessStatus;
import com.storeanalytics.store.service.StoreDataStatusView;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class WeeklySnapshotQualityPolicyTest {

    private final WeeklySnapshotPolicyV1 policy = new WeeklySnapshotPolicyV1();

    @Test
    void keepsUsableFactsButDeclaresLocalizedProblemsAsPartial() {
        LocalDate periodEnd = LocalDate.of(2026, 7, 26);
        StoreDataStatusView source = mock(StoreDataStatusView.class);
        when(source.status()).thenReturn(StoreDataFreshnessStatus.CURRENT);
        when(source.dataThroughDate()).thenReturn(periodEnd);
        when(source.openQualityIssueCount()).thenReturn(1L);

        SnapshotQualityDecision decision = policy.quality(
                source,
                new StoreKpiDataQuality(false, 10, 1, 1, 0, 1, 1),
                new AttachRateDataQuality(1, 0, 0),
                periodEnd
        );

        assertThat(decision.status()).isEqualTo(QualityStatus.PARTIAL);
        assertThat(decision.limitations())
                .extracting(limitation -> limitation.code())
                .containsExactly(
                        "COST_DATA_INCOMPLETE",
                        "CLASSIFICATION_QUALITY_LIMITED",
                        "ATTACH_QUALITY_LIMITED"
                );
        assertThat(decision.unavailableEvidence()).hasSize(3);
    }
}
