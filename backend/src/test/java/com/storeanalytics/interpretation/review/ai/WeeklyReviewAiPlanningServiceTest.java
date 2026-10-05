package com.storeanalytics.interpretation.review.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeanalytics.integration.llm.yandex.YandexLlmProperties;
import com.storeanalytics.interpretation.review.SellerWeeklyReviewService;
import com.storeanalytics.interpretation.review.SellerWeeklyReviewView;
import com.storeanalytics.interpretation.review.SellerWeeklyV3AssemblerTest;
import com.storeanalytics.interpretation.review.SellerWeeklyHistoricalAiPlanningService;
import com.storeanalytics.interpretation.review.WeeklyReviewV3Response;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse;
import com.storeanalytics.interpretation.snapshot.WeeklySnapshotPlanningStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WeeklyReviewAiPlanningServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-05T06:00:00Z");
    private final WeeklyReviewAiJobStore jobs = mock(WeeklyReviewAiJobStore.class);
    private final WeeklySnapshotPlanningStore stores = mock(WeeklySnapshotPlanningStore.class);
    private final SellerWeeklyReviewService reviews = mock(SellerWeeklyReviewService.class);
    private final YandexLlmProperties yandex = mock(YandexLlmProperties.class);
    private final WeeklyReviewAiGenerationProperties properties =
            WeeklyReviewAiTestProperties.properties(true, true, true);
    private final WeeklyReviewAiPlanningService planner = new WeeklyReviewAiPlanningService(jobs, properties, yandex,
            Clock.fixed(NOW, ZoneOffset.UTC), stores, reviews);

    @Test
    void failedStoreDoesNotStarveAnotherStoreAndConfiguredRetryCapIsUsed() {
        UUID failed = UUID.randomUUID();
        UUID available = UUID.randomUUID();
        var enhancement = SellerWeeklyV3AssemblerTest.syntheticResponse().aiEnhancement();
        var report = mock(WeeklyReviewV3Response.class);
        UUID snapshot = UUID.randomUUID();
        when(report.reportState()).thenReturn(WeeklyReviewResponse.ReportState.READY);
        when(report.provenance()).thenReturn(new WeeklyReviewResponse.Provenance(
                snapshot.toString(), 1, NOW, NOW, false, null));
        when(report.aiEnhancement()).thenReturn(enhancement);
        when(jobs.activeReportContractVersion()).thenReturn(3);
        when(yandex.getModelUri()).thenReturn("synthetic-model");
        when(stores.activeStoresAfter(null, properties.batchSize())).thenReturn(List.of(
                new WeeklySnapshotPlanningStore.StoreTarget(failed, "Europe/Kaliningrad"),
                new WeeklySnapshotPlanningStore.StoreTarget(available, "Europe/Kaliningrad")));
        when(reviews.current(failed)).thenThrow(new IllegalStateException("Synthetic source read failure"));
        when(reviews.current(available)).thenReturn(new SellerWeeklyReviewView(
                SellerWeeklyReviewView.Freshness.CURRENT, report));
        when(jobs.enqueueAutomaticSellerWeek(snapshot, properties.providerCode(), "synthetic-model",
                properties.maxProviderCalls(), NOW, properties.jobDeadline())).thenReturn(true);

        assertThat(planner.plan()).isOne();

        verify(reviews).current(available);
        verify(jobs).enqueueAutomaticSellerWeek(snapshot, properties.providerCode(), "synthetic-model",
                properties.maxProviderCalls(), NOW, properties.jobDeadline());
    }

    @Test
    void preparingStoreDoesNotCreateAnAiJob() {
        UUID store = UUID.randomUUID();
        when(jobs.activeReportContractVersion()).thenReturn(3);
        when(stores.activeStoresAfter(null, properties.batchSize())).thenReturn(List.of(
                new WeeklySnapshotPlanningStore.StoreTarget(store, "Europe/Kaliningrad")));
        when(reviews.current(store)).thenReturn(new SellerWeeklyReviewView(
                SellerWeeklyReviewView.Freshness.PREPARING, null));

        assertThat(planner.plan()).isZero();

        verify(jobs, never()).enqueueAutomaticSellerWeek(any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any());
    }

    @Test
    void historicalModeDelegatesOnlyToTheExactBacklogPlanner() {
        var historical = mock(SellerWeeklyHistoricalAiPlanningService.class);
        when(historical.historicalModeEnabled()).thenReturn(true);
        when(jobs.activeReportContractVersion()).thenReturn(3);
        when(historical.plan()).thenReturn(3);
        var selected = new WeeklyReviewAiPlanningService(jobs, properties, yandex,
                Clock.fixed(NOW, ZoneOffset.UTC), stores, reviews, historical);
        assertThat(selected.plan()).isEqualTo(3);
        verify(historical).plan();
        org.mockito.Mockito.verifyNoInteractions(stores, reviews);
        verify(jobs, never()).enqueueLatest(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any());
    }

    @Test
    void invalidHistoricalContractNeverFallsBackToLegacyPlanning() {
        var historical = mock(SellerWeeklyHistoricalAiPlanningService.class);
        when(historical.historicalModeEnabled()).thenReturn(true);
        when(jobs.activeReportContractVersion()).thenReturn(2);
        var selected = new WeeklyReviewAiPlanningService(jobs, properties, yandex,
                Clock.fixed(NOW, ZoneOffset.UTC), stores, reviews, historical);
        org.assertj.core.api.Assertions.assertThatThrownBy(selected::plan)
                .isInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.verifyNoInteractions(stores, reviews);
        verify(historical, never()).plan();
        verify(jobs, never()).enqueueLatest(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any());
    }
}
