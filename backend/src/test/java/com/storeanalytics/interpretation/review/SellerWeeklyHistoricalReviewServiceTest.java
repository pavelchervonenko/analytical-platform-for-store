package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeanalytics.interpretation.review.ai.SellerWeeklyReviewAiEnricher;
import com.storeanalytics.interpretation.review.ai.SellerWeeklyReviewAiReadSupport;
import com.storeanalytics.interpretation.review.ai.SellerWeeklyReviewAiStateResolver;
import com.storeanalytics.interpretation.review.ai.WeeklyReviewAiEnrichmentStore;
import com.storeanalytics.interpretation.review.ai.WeeklyReviewAiTestProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class SellerWeeklyHistoricalReviewServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");
    private static final LocalDate START = LocalDate.parse("2026-09-14");
    private final UUID store = UUID.randomUUID();
    private final SellerWeeklyHistoricalReadService reads = mock(SellerWeeklyHistoricalReadService.class);
    private final SellerWeeklyHistoricalPlanningService planner = mock(SellerWeeklyHistoricalPlanningService.class);
    private final WeeklyReviewAiEnrichmentStore enrichments = mock(WeeklyReviewAiEnrichmentStore.class);
    private final SellerWeeklyReviewAiStateResolver states = mock(SellerWeeklyReviewAiStateResolver.class);
    private final SellerWeeklyReviewAiReadSupport ai = new SellerWeeklyReviewAiReadSupport(
            WeeklyReviewAiTestProperties.properties(true, false, false), enrichments,
            mock(SellerWeeklyReviewAiEnricher.class), states);
    private final SellerWeeklyHistoricalReviewService service = new SellerWeeklyHistoricalReviewService(
            reads, planner, ai, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void preparingOrStaleReadsCannotGenerateOrReadOptionalAi() {
        when(reads.assess(store, START)).thenReturn(new SellerWeeklyV3ReadResult(
                SellerWeeklyV3ReadResult.State.PREPARING, Optional.empty()));
        assertThat(service.period(store, START).freshness()).isEqualTo(SellerWeeklyReviewView.Freshness.PREPARING);
        var saved = mock(PersistedWeeklyReviewV3Snapshot.class);
        var report = mock(WeeklyReviewV3Response.class);
        when(saved.response()).thenReturn(report);
        when(reads.assess(store, START)).thenReturn(new SellerWeeklyV3ReadResult(
                SellerWeeklyV3ReadResult.State.STALE, Optional.of(saved)));
        assertThat(service.period(store, START).report()).isSameAs(report);
        verifyNoInteractions(planner, enrichments, states);
    }

    @Test
    void optionalAiFailureDoesNotHideTheHistoricalDeterministicReport() {
        UUID id = UUID.randomUUID();
        var codec = new WeeklyReviewV3SnapshotCodec();
        var report = codec.deserialize(codec.serialize(SellerWeeklyV3AssemblerTest.syntheticResponse())
                .replace("test-seller-v3", id.toString()));
        var saved = mock(PersistedWeeklyReviewV3Snapshot.class);
        when(saved.response()).thenReturn(report);
        when(reads.assess(store, START)).thenReturn(new SellerWeeklyV3ReadResult(
                SellerWeeklyV3ReadResult.State.CURRENT, Optional.of(saved)));
        when(states.apply(report, NOW)).thenReturn(report);
        when(enrichments.findPublishedSeller(id, NOW)).thenThrow(new DataAccessResourceFailureException("synthetic"));
        var result = service.period(store, START);
        assertThat(result.report().results()).isEqualTo(report.results());
        assertThat(result.report().aiEnhancement().state()).isEqualTo(WeeklyReviewResponse.AiState.UNAVAILABLE);
        verifyNoInteractions(planner);
    }

    @Test
    void internalRefreshUsesExactPeriodAndExpectedTimezoneWithoutAiPlanning() {
        when(planner.evaluate(store, START, "UTC")).thenReturn(new SellerWeeklyV3PlanningResult(
                SellerWeeklyV3PlanningResult.Outcome.DEFERRED, new SellerWeeklyV3ReadResult(
                        SellerWeeklyV3ReadResult.State.PREPARING, Optional.empty())));
        assertThat(service.refresh(store, START, "UTC").freshness())
                .isEqualTo(SellerWeeklyReviewView.Freshness.PREPARING);
        verify(planner).evaluate(store, START, "UTC");
        verifyNoInteractions(reads, enrichments, states);
    }
}
