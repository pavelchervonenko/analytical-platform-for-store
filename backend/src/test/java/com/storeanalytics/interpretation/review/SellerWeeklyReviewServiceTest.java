package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeanalytics.interpretation.review.ai.SellerWeeklyReviewAiEnricher;
import com.storeanalytics.interpretation.review.ai.SellerWeeklyReviewAiReadSupport;
import com.storeanalytics.interpretation.review.ai.SellerWeeklyReviewAiStateResolver;
import com.storeanalytics.interpretation.review.ai.WeeklyReviewAiEnrichmentStore;
import com.storeanalytics.interpretation.review.ai.WeeklyReviewAiTestProperties;
import com.storeanalytics.metrics.exception.StoreNotFoundException;
import com.storeanalytics.store.repository.StoreRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class SellerWeeklyReviewServiceTest {
    private final UUID storeId = UUID.randomUUID();
    private final StoreRepository stores = mock(StoreRepository.class);
    private final SellerWeeklyV3ReadService reads = mock(SellerWeeklyV3ReadService.class);
    private final SellerWeeklyV3PlanningService planner = mock(SellerWeeklyV3PlanningService.class);
    private final SellerWeeklyReviewService service = new SellerWeeklyReviewService(stores, reads, planner);

    @Test
    void getDoesNotGenerateOrFallbackToStoreFacts() {
        when(stores.existsById(storeId)).thenReturn(true);
        when(reads.assessForPlanning(storeId)).thenReturn(new SellerWeeklyV3ReadResult(
                SellerWeeklyV3ReadResult.State.PREPARING, Optional.empty()));
        assertThat(service.current(storeId)).isEqualTo(new SellerWeeklyReviewView(
                SellerWeeklyReviewView.Freshness.PREPARING, null));
        verifyNoInteractions(planner);
    }

    @Test
    void staleReportRetainsItsOriginalPayloadAndQuality() {
        when(stores.existsById(storeId)).thenReturn(true);
        var snapshot = mock(PersistedWeeklyReviewV3Snapshot.class);
        var report = mock(WeeklyReviewV3Response.class);
        when(snapshot.response()).thenReturn(report);
        when(reads.assessForPlanning(storeId)).thenReturn(new SellerWeeklyV3ReadResult(
                SellerWeeklyV3ReadResult.State.STALE, Optional.of(snapshot)));
        assertThat(service.current(storeId)).isEqualTo(new SellerWeeklyReviewView(
                SellerWeeklyReviewView.Freshness.STALE, report));
        verifyNoInteractions(planner);
    }

    @Test
    void optionalAiStorageFailureKeepsDeterministicSellerReportAvailable() {
        Instant now = Instant.parse("2026-08-24T08:00:00Z");
        UUID snapshotId = UUID.randomUUID();
        var codec = new WeeklyReviewV3SnapshotCodec();
        var report = codec.deserialize(codec.serialize(SellerWeeklyV3AssemblerTest.syntheticResponse())
                .replace("test-seller-v3", snapshotId.toString()));
        var snapshot = mock(PersistedWeeklyReviewV3Snapshot.class);
        var enrichments = mock(WeeklyReviewAiEnrichmentStore.class);
        var states = mock(SellerWeeklyReviewAiStateResolver.class);
        var support = new SellerWeeklyReviewAiReadSupport(
                WeeklyReviewAiTestProperties.properties(true, false, false), enrichments,
                mock(SellerWeeklyReviewAiEnricher.class), states);
        var withAi = new SellerWeeklyReviewService(stores, reads, planner, support,
                Clock.fixed(now, ZoneOffset.UTC));
        when(stores.existsById(storeId)).thenReturn(true);
        when(snapshot.response()).thenReturn(report);
        when(reads.assessForPlanning(storeId)).thenReturn(new SellerWeeklyV3ReadResult(
                SellerWeeklyV3ReadResult.State.CURRENT, Optional.of(snapshot)));
        when(states.apply(report, now)).thenReturn(report);
        when(enrichments.findPublishedSeller(snapshotId, now))
                .thenThrow(new DataAccessResourceFailureException("synthetic optional AI storage failure"));

        var result = withAi.current(storeId);

        assertThat(result.freshness()).isEqualTo(SellerWeeklyReviewView.Freshness.CURRENT);
        assertThat(result.report().results()).isEqualTo(report.results());
        assertThat(result.report().additionalSales()).isEqualTo(report.additionalSales());
        assertThat(result.report().employees()).isEqualTo(report.employees());
        assertThat(result.report().aiEnhancement().state()).isEqualTo(WeeklyReviewResponse.AiState.UNAVAILABLE);
        verifyNoInteractions(planner);
    }

    @Test
    void generationUsesStableFencedPlannerAndMayRemainPreparing() {
        when(stores.existsById(storeId)).thenReturn(true);
        when(planner.evaluate(storeId)).thenReturn(new SellerWeeklyV3PlanningResult(
                SellerWeeklyV3PlanningResult.Outcome.DEFERRED, new SellerWeeklyV3ReadResult(
                        SellerWeeklyV3ReadResult.State.PREPARING, Optional.empty())));
        assertThat(service.generate(storeId).freshness()).isEqualTo(SellerWeeklyReviewView.Freshness.PREPARING);
        verify(planner).evaluate(storeId);
        verifyNoInteractions(reads);
    }

    @Test
    void missingStoreNeverReadsOrGenerates() {
        assertThatThrownBy(() -> service.current(storeId)).isInstanceOf(StoreNotFoundException.class);
        assertThatThrownBy(() -> service.generate(storeId)).isInstanceOf(StoreNotFoundException.class);
        verifyNoInteractions(reads, planner);
    }

    @Test
    void freshnessCannotMislabelMissingOrPresentContent() {
        assertThatThrownBy(() -> new SellerWeeklyReviewView(SellerWeeklyReviewView.Freshness.CURRENT, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SellerWeeklyReviewView(SellerWeeklyReviewView.Freshness.STALE, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SellerWeeklyReviewView(SellerWeeklyReviewView.Freshness.PREPARING,
                mock(WeeklyReviewV3Response.class))).isInstanceOf(IllegalArgumentException.class);
    }
}
