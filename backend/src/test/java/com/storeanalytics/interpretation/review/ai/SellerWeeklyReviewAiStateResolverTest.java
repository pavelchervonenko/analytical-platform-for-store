package com.storeanalytics.interpretation.review.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse;
import com.storeanalytics.interpretation.review.WeeklyReviewV3Response;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SellerWeeklyReviewAiStateResolverTest {
    @Test
    void spentOrExactOtherRevisionIsUnavailableRatherThanAnInfinitePreparation() {
        assertThat(resolve(true)).isEqualTo(WeeklyReviewResponse.AiState.UNAVAILABLE);
    }

    @Test
    void unpaidOtherRevisionStillWaitsForTheFreeAutomaticRebind() {
        assertThat(resolve(false)).isEqualTo(WeeklyReviewResponse.AiState.PREPARING);
    }

    private WeeklyReviewResponse.AiState resolve(boolean blocked) {
        var now = Instant.parse("2026-10-05T06:00:00Z");
        var snapshotId = UUID.randomUUID();
        var jobs = mock(WeeklyReviewAiJobStore.class);
        var report = mock(WeeklyReviewV3Response.class);
        when(report.reportState()).thenReturn(WeeklyReviewResponse.ReportState.READY);
        when(report.provenance()).thenReturn(new WeeklyReviewResponse.Provenance(
                snapshotId.toString(), 1, now, now, false, null));
        when(jobs.findBySnapshot(snapshotId)).thenReturn(Optional.empty());
        when(jobs.hasSellerWeekPublicationBlocker(snapshotId, now)).thenReturn(blocked);
        new SellerWeeklyReviewAiStateResolver(WeeklyReviewAiTestProperties.properties(true, true, true), jobs)
                .apply(report, now);
        var enhancement = ArgumentCaptor.forClass(WeeklyReviewResponse.AiEnhancement.class);
        verify(report).withAiEnhancement(enhancement.capture());
        return enhancement.getValue().state();
    }
}
